package main

import (
	"fmt"
	"log"
	"math"
	"sync"
	"time"
)

// outFrame is a frame waiting to be written. Session frames are kept in the
// subflow log until the peer confirms them (SUBACK), so they can be resent if
// that subflow dies.
type outFrame struct {
	typ     byte
	payload []byte
	flow    *flow // DATA only: for per-flow accounting when dequeued
	copied  bool  // already sent a second time over a faster subflow
}

func (f *outFrame) wire() int { return recordHead + 1 + len(f.payload) + 16 }

func isSessionFrame(t byte) bool {
	return t == fOpen || t == fData || t == fAck || t == fFin || t == fRst
}

type logEntry struct {
	end uint64
	f   *outFrame
}

type rttSample struct {
	at  time.Time
	rtt time.Duration
}

type subflow struct {
	sess    *session
	id      byte
	link    byte
	standby bool
	sc      *secureConn

	ctrl []*outFrame // subflow-local frames, highest priority
	log  []logEntry

	written  uint64 // record bytes handed to the socket
	subacked uint64 // record bytes the peer confirmed
	received uint64 // record bytes read from the peer
	ackSent  uint64 // value of the last SUBACK we sent
	ackAt    time.Time

	rate       float64 // bytes per second
	minRtt     time.Duration
	rtts       []rttSample
	lastRecv   time.Time
	sampleAt   time.Time
	sampleAck  uint64
	sampleBusy bool
	pingID     uint32
	dead       bool
	suspect    bool      // silent while the others are fine: no new data, unconfirmed data copied elsewhere
	provenAt   time.Time // last time rate was measured with a full pipe
	created    time.Time
}

func (s *subflow) inflight() uint64 { return s.written - s.subacked }

// est is the expected arrival time (seconds) of a chunk sent now on this subflow:
// the time to drain what is queued ahead of it, plus the one-way delay. Data that is
// merely in flight (up to rate x RTT) is not a queue: it does not delay the chunk.
func (s *subflow) est(size int) float64 {
	r := s.rate
	if r < 64*1024 {
		r = 64 * 1024
	}
	q := float64(s.inflight()+uint64(size)) - r*s.minRtt.Seconds()
	if q < 0 {
		q = 0
	}
	return q/r + s.minRtt.Seconds()/2
}

// cap is the most unconfirmed data this subflow may hold: keeps queues short so
// the scheduler's choices stay fresh and latency stays low.
func (s *subflow) cap() uint64 {
	c := 4 * s.rate * s.minRtt.Seconds()
	if c < 256*1024 {
		c = 256 * 1024
	}
	if c > 64<<20 {
		c = 64 << 20
	}
	return uint64(c)
}

type session struct {
	id    [16]byte
	mu    sync.Mutex
	sched *sync.Cond // subflow writers wait here

	subs   map[byte]*subflow
	flows  map[uint32]*flow
	closed map[uint32]time.Time

	reinj  []*outFrame // frames from dead subflows, resent first, in order
	oppQ   []*outFrame // copies of chunks the receiver is stuck on, for fast subflows
	ctrlQ  []*outFrame // session control frames (ACK, FIN, RST, GONE)
	rr     []*flow     // flows with DATA waiting, served round-robin
	queued int         // DATA bytes waiting in rr
	empty  time.Time   // when the last subflow went away
	start  time.Time
	done   chan struct{}
	server *server
}

func newSession(id [16]byte, srv *server) *session {
	s := &session{
		id: id, subs: map[byte]*subflow{}, flows: map[uint32]*flow{}, closed: map[uint32]time.Time{},
		start: time.Now(), empty: time.Now(), done: make(chan struct{}), server: srv,
	}
	s.sched = sync.NewCond(&s.mu)
	go s.ticker()
	return s
}

func (s *session) now() uint64 { return uint64(time.Since(s.start).Nanoseconds()) }

// ---------------------------------------------------------------- subflows

func (s *session) attach(sc *secureConn, subID, link byte, standby bool) *subflow {
	s.mu.Lock()
	if old := s.subs[subID]; old != nil {
		s.killLocked(old, "replaced")
	}
	sf := &subflow{
		sess: s, id: subID, link: link, standby: standby, sc: sc,
		rate: 1 << 20, minRtt: 50 * time.Millisecond, lastRecv: time.Now(), sampleAt: time.Now(), ackAt: time.Now(),
		created: time.Now(),
	}
	s.subs[subID] = sf
	s.sched.Broadcast()
	s.mu.Unlock()
	return sf
}

func (s *session) kill(sf *subflow, why string) { s.killWith(sf, nil, why) }

// killWith also resends unsent, a frame the writer took but could not write.
func (s *session) killWith(sf *subflow, unsent *outFrame, why string) {
	s.mu.Lock()
	s.killLocked(sf, why)
	if unsent != nil && isSessionFrame(unsent.typ) {
		s.reinj = append(s.reinj, unsent)
	}
	s.mu.Unlock()
}

// killLocked removes a subflow, queues everything the peer has not confirmed for
// resending on the others, and tells the peer.
func (s *session) killLocked(sf *subflow, why string) {
	if sf.dead {
		return
	}
	sf.dead = true
	_ = sf.sc.c.Close()
	if s.subs[sf.id] == sf {
		delete(s.subs, sf.id)
	}
	var again []*outFrame
	for _, e := range sf.log {
		again = append(again, e.f)
	}
	sf.log = nil
	s.reinj = append(again, s.reinj...)
	if len(s.subs) == 0 {
		s.empty = time.Now()
	}
	s.ctrlQ = append(s.ctrlQ, &outFrame{typ: fGone, payload: []byte{sf.id}})
	// The peer may have lost our latest window updates: send them again.
	for _, f := range s.flows {
		if a := f.ackFrame(); a != nil {
			s.ctrlQ = append(s.ctrlQ, a)
		}
	}
	if debug {
		log.Printf("subflow %d (link %d) gone: %s; resending %d frames; inflight %d cap %d rate %.0f rtt %v rr %d ctrl %d",
			sf.id, sf.link, why, len(again), sf.inflight(), sf.cap(), sf.rate, sf.minRtt, len(s.rr), len(s.ctrlQ))
		for _, f := range s.flows {
			log.Printf("  flow %d sendSeq %d peerLimit %d out %d next %d queue %d fin %d", f.id, f.sendSeq, f.peerLimit, f.outBytes, f.next, len(f.queue), f.finAt)
		}
	}
	s.sched.Broadcast()
}

// usable: standby subflows carry traffic only when no normal subflow is alive;
// a suspect (silent) subflow only when nothing healthy is left.
func (s *session) usable(sf *subflow) bool {
	if sf.dead {
		return false
	}
	healthy := func(o *subflow) bool { return !o.dead && !o.suspect }
	if sf.suspect {
		for _, o := range s.subs {
			if healthy(o) {
				return false
			}
		}
		return true
	}
	if !sf.standby {
		return true
	}
	for _, o := range s.subs {
		if healthy(o) && !o.standby {
			return false
		}
	}
	return true
}

// minEst is the best arrival time over all usable subflows, including full ones:
// if the fastest is momentarily full, waiting for it usually beats a slow link.
func (s *session) minEst(size int) float64 {
	m := math.Inf(1)
	for _, o := range s.subs {
		if s.usable(o) && !s.tooSlow(o) { // a benched subflow is no alternative
			if e := o.est(size); e < m {
				m = e
			}
		}
	}
	return m
}

// next returns the next frame for subflow sf. prev is the frame just written
// (logged here, under the lock). With block=false it returns nil if nothing is
// ready; with block=true it waits, and returns nil only when sf is dead.
func (s *session) next(sf *subflow, prev *outFrame, written uint64, block bool) *outFrame {
	s.mu.Lock()
	defer s.mu.Unlock()
	sf.written = written
	if prev != nil && isSessionFrame(prev.typ) {
		if sf.dead {
			s.reinj = append(s.reinj, prev) // written to a socket that has since died
		} else {
			sf.log = append(sf.log, logEntry{end: written, f: prev})
		}
	}
	for {
		if sf.dead {
			return nil
		}
		if len(sf.ctrl) > 0 {
			f := sf.ctrl[0]
			sf.ctrl = sf.ctrl[1:]
			return f
		}
		if s.usable(sf) {
			if len(s.reinj) > 0 {
				f := s.reinj[0]
				s.reinj = s.reinj[1:]
				return f
			}
			if len(s.ctrlQ) > 0 {
				f := s.ctrlQ[0]
				s.ctrlQ = s.ctrlQ[1:]
				return f
			}
			if sf.inflight() < sf.cap() && !s.tooSlow(sf) && sf.est(chunkSize) <= 1.2*s.minEst(chunkSize)+0.002+s.backlogDelay(sf) {
				if len(s.oppQ) == 0 && len(s.rr) == 0 {
					s.opportunistic(sf)
				}
				if len(s.oppQ) > 0 {
					f := s.oppQ[0]
					s.oppQ = s.oppQ[1:]
					return f
				}
				if len(s.rr) > 0 {
					return s.popData()
				}
			}
		}
		if !block {
			return nil
		}
		s.sched.Wait()
	}
}

// tooSlow: a subflow proven (in the last 5 s) to give under 10% of the best one's speed
// only causes waiting for its late chunks, so it carries no bulk data (it is still there
// for failover, and is tried again when the measurement expires).
func (s *session) tooSlow(sf *subflow) bool {
	// Not during its first 3 s (a long-delay link always looks slow while TCP ramps up),
	// and only while the measurement is fresh (then it is tried again).
	if time.Since(sf.created) < 3*time.Second || time.Since(sf.provenAt) > 5*time.Second {
		return false
	}
	best := 0.0
	for _, o := range s.subs {
		if s.usable(o) && o.rate > best {
			best = o.rate
		}
	}
	return sf.rate < 0.1*best
}

// opportunistic: a flow is window-blocked (the receiver's buffer is full) and the chunk
// the receiver is missing sits on a subflow that will deliver it clearly later than sf
// would. Queue copies of that chunk and the ones after it on the same subflow (up to 32)
// for fast subflows; whichever copy arrives first is used.
func (s *session) opportunistic(sf *subflow) {
	mine := sf.est(chunkSize)
	now := time.Now()
	for _, f := range s.flows {
		if f.peerLimit-f.sendSeq >= chunkSize || f.peerNext >= f.sendSeq || now.Sub(f.oppAt) < 10*time.Millisecond {
			continue
		}
		f.oppAt = now
		for _, o := range s.subs {
			if o == sf || o.dead || (!o.suspect && o.est(0) <= mine+0.015) {
				continue
			}
			found := false
			for _, e := range o.log {
				if e.f == nil || e.f.typ != fData || e.f.flow != f {
					continue
				}
				seq := be64(e.f.payload[4:])
				end := seq + uint64(len(e.f.payload)-12)
				if !found && f.peerNext >= seq && f.peerNext < end {
					found = true
				}
				if found && !e.f.copied {
					e.f.copied = true
					s.oppQ = append(s.oppQ, e.f)
					if len(s.oppQ) >= 32 {
						return
					}
				}
			}
			if found {
				return
			}
		}
	}
}

// backlogDelay: how long the subflows faster than sf need to send everything already
// waiting. A slower subflow is worth using when its chunk arrives before they would get
// to it (bulk transfers); with little waiting (interactive traffic) it stays unused.
func (s *session) backlogDelay(sf *subflow) float64 {
	if s.queued == 0 {
		return 0
	}
	mine := sf.est(chunkSize)
	rate := 0.0
	for _, o := range s.subs {
		if o != sf && s.usable(o) && !s.tooSlow(o) && o.est(chunkSize) < mine {
			rate += math.Max(o.rate, 64*1024)
		}
	}
	if rate == 0 {
		return 0
	}
	return float64(s.queued) / rate
}

// popData takes one DATA frame, round-robin across flows.
func (s *session) popData() *outFrame {
	f := s.rr[0]
	fr := f.out[0]
	f.out = f.out[1:]
	f.outBytes -= len(fr.payload)
	s.queued -= len(fr.payload)
	if len(f.out) > 0 {
		s.rr = append(s.rr[1:], f)
	} else {
		s.rr = s.rr[1:]
		f.inRR = false
	}
	f.rcond.Broadcast()
	return fr
}

func (s *session) queueData(f *flow, fr *outFrame) {
	f.out = append(f.out, fr)
	f.outBytes += len(fr.payload)
	s.queued += len(fr.payload)
	if !f.inRR {
		f.inRR = true
		s.rr = append(s.rr, f)
	}
	s.sched.Broadcast()
}

func (s *session) queueCtrl(fr *outFrame) {
	s.ctrlQ = append(s.ctrlQ, fr)
	s.sched.Broadcast()
}

func (s *subflow) writeLoop() {
	var prev *outFrame
	var written uint64
	for {
		f := s.sess.next(s, prev, written, false)
		if f == nil {
			if err := s.sc.flush(); err != nil {
				s.sess.kill(s, "write: "+err.Error())
				return
			}
			f = s.sess.next(s, nil, written, true)
			if f == nil {
				return
			}
		}
		if _, err := s.sc.writeFrame(f.typ, f.payload); err != nil {
			s.sess.killWith(s, f, "write: "+err.Error())
			return
		}
		prev, written = f, s.sc.written
	}
}

func (s *subflow) readLoop() {
	for {
		typ, p, err := s.sc.readFrame()
		if err != nil {
			s.sess.kill(s, "read: "+err.Error())
			return
		}
		s.sess.handle(s, typ, p)
	}
}

// ---------------------------------------------------------------- incoming frames

func (s *session) handle(sf *subflow, typ byte, p []byte) {
	var after []func()
	s.mu.Lock()
	if sf.dead {
		s.mu.Unlock()
		return
	}
	sf.lastRecv = time.Now()
	sf.received = sf.sc.received
	if sf.suspect {
		sf.suspect = false // it was only slow
		s.sched.Broadcast()
	}
	switch typ {
	case fPing:
		sf.ctrl = append(sf.ctrl, &outFrame{typ: fPong, payload: append([]byte(nil), p...)})
		s.sched.Broadcast()
	case fPong:
		if len(p) >= 12 {
			rtt := time.Duration(s.now() - be64(p[4:]))
			sf.rtts = append(sf.rtts, rttSample{time.Now(), rtt})
			if rtt < sf.minRtt || len(sf.rtts) == 1 {
				sf.minRtt = rtt
			}
		}
	case fSubAck:
		if len(p) >= 8 {
			v := be64(p)
			if v > sf.subacked && v <= sf.written {
				sf.subacked = v
				i := 0
				for i < len(sf.log) && sf.log[i].end <= v {
					sf.log[i].f = nil
					i++
				}
				sf.log = sf.log[i:]
				s.sched.Broadcast()
			}
		}
	case fGone:
		if len(p) >= 1 {
			if o := s.subs[p[0]]; o != nil && o != sf {
				s.killLocked(o, "peer says gone")
			}
		}
	case fStandby:
		if len(p) >= 1 {
			sf.standby = p[0] != 0
			s.sched.Broadcast()
		}
	case fOpen:
		after = append(after, s.onOpen(p)...)
	case fData:
		after = append(after, s.onData(p)...)
	case fAck:
		s.onAck(p)
	case fFin:
		s.onFin(p)
	case fRst:
		if len(p) >= 4 {
			if f := s.flows[be32(p)]; f != nil {
				s.dropOutLocked(f) // the phone no longer wants it
				after = append(after, s.closeFlowLocked(f, false, 0)...)
			} else if _, gone := s.closed[be32(p)]; !gone {
				s.closed[be32(p)] = time.Now() // reset before its OPEN arrived
			}
		}
	case fDgram:
		after = append(after, s.onDgram(p)...)
	}
	// Confirm what we received: often enough for the peer's scheduler and log.
	if sf.received-sf.ackSent >= 16*1024 {
		s.subAckLocked(sf)
	}
	s.mu.Unlock()
	for _, fn := range after {
		fn()
	}
}

func (s *session) subAckLocked(sf *subflow) {
	sf.ackSent = sf.received
	sf.ackAt = time.Now()
	sf.ctrl = append(sf.ctrl, &outFrame{typ: fSubAck, payload: put64(nil, sf.received)})
	s.sched.Broadcast()
}

// ---------------------------------------------------------------- housekeeping

func (s *session) ticker() {
	t := time.NewTicker(5 * time.Millisecond)
	defer t.Stop()
	n := 0
	for {
		select {
		case <-s.done:
			return
		case <-t.C:
		}
		n++
		var after []func()
		s.mu.Lock()
		now := time.Now()
		for _, sf := range s.subs {
			if sf.received != sf.ackSent && now.Sub(sf.ackAt) >= 10*time.Millisecond {
				s.subAckLocked(sf)
			}
			if n%20 == 0 { // 100 ms: rate sample
				dt := now.Sub(sf.sampleAt).Seconds()
				d := sf.subacked - sf.sampleAck
				// Busy = the pipe was full, so delivery speed is the link's real speed.
				busy := float64(sf.inflight()) > math.Max(32*1024, sf.rate*sf.minRtt.Seconds())
				if dt > 0 && d > 0 {
					inst := float64(d) / dt
					if sf.sampleBusy && busy {
						sf.rate = 0.7*sf.rate + 0.3*inst
						sf.provenAt = now
					} else if inst > sf.rate {
						sf.rate = 0.7*sf.rate + 0.3*inst
					}
				}
				sf.sampleAt, sf.sampleAck, sf.sampleBusy = now, sf.subacked, busy
			}
			if n%50 == 0 { // 250 ms: ping, rtt window, liveness
				sf.pingID++
				sf.ctrl = append(sf.ctrl, &outFrame{typ: fPing, payload: put64(put32(nil, sf.pingID), s.now())})
				keep := sf.rtts[:0]
				m := time.Duration(0)
				for _, r := range sf.rtts { // 30 s window: pings under load read high
					if now.Sub(r.at) < 30*time.Second {
						keep = append(keep, r)
						if m == 0 || r.rtt < m {
							m = r.rtt
						}
					}
				}
				sf.rtts = keep
				if m > 0 {
					sf.minRtt = m
				}
				if now.Sub(sf.lastRecv) > 2500*time.Millisecond {
					s.killLocked(sf, "silent")
				}
			}
			// Silent for 0.7 s while another subflow is talking: stop using it and resend its
			// unconfirmed data elsewhere right away (it is not killed: it may just be slow).
			if !sf.dead && !sf.suspect && now.Sub(sf.lastRecv) > 700*time.Millisecond {
				fresh := false
				for _, o := range s.subs {
					if o != sf && !o.dead && now.Sub(o.lastRecv) < 300*time.Millisecond {
						fresh = true
					}
				}
				if fresh {
					sf.suspect = true
					var again []*outFrame
					for _, e := range sf.log {
						again = append(again, e.f)
					}
					s.reinj = append(again, s.reinj...)
					if debug {
						log.Printf("subflow %d suspect: copying %d frames to other subflows", sf.id, len(again))
					}
					s.sched.Broadcast()
				}
			}
		}
		for _, f := range s.flows {
			if (f.ackDue || len(f.ooo) > 0) && now.Sub(f.ackAt) >= 20*time.Millisecond {
				if a := f.ackFrame(); a != nil {
					s.ctrlQ = append(s.ctrlQ, a)
				}
			}
			if n%200 == 0 {
				after = append(after, s.idleCheckLocked(f, now)...)
			}
		}
		if debug && n%200 == 0 && len(s.rr)+len(s.flows) > 0 {
			line := ""
			for _, sf := range s.subs {
				line += fmt.Sprintf(" [%d L%d rate %.1fMbps rtt %v infl %dK cap %dK est %.1fms slow %v susp %v]",
					sf.id, sf.link, sf.rate*8/1e6, sf.minRtt.Round(time.Millisecond), sf.inflight()/1024, sf.cap()/1024,
					sf.est(chunkSize)*1000, s.tooSlow(sf), sf.suspect)
			}
			log.Printf("state flows=%d rr=%d opp=%d%s", len(s.flows), len(s.rr), len(s.oppQ), line)
		}
		if n%2000 == 0 {
			for id, at := range s.closed {
				if now.Sub(at) > 5*time.Minute {
					delete(s.closed, id)
				}
			}
		}
		if len(s.rr) > 0 || len(s.ctrlQ) > 0 || len(s.reinj) > 0 {
			s.sched.Broadcast()
		}
		gc := len(s.subs) == 0 && now.Sub(s.empty) > 120*time.Second
		if gc {
			for _, f := range s.flows {
				s.dropOutLocked(f)
				after = append(after, s.closeFlowLocked(f, false, 0)...)
			}
		}
		s.mu.Unlock()
		for _, fn := range after {
			fn()
		}
		if gc {
			s.server.drop(s)
			close(s.done)
			return
		}
	}
}
