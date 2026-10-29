package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"syscall"
	"time"
)

const (
	recvWindow  = 4 << 20   // what the server accepts per flow (phone → internet)
	outCap      = 512 << 10 // DATA waiting per flow before we stop reading its source
	dialTimeout = 10 * time.Second
)

type flow struct {
	id    uint32
	proto byte
	sess  *session
	rcond *sync.Cond // source reader waits for window / queue room
	wcond *sync.Cond // target writer waits for data

	opened bool // OPEN seen (DATA can arrive first after a resend)
	conn   net.Conn
	udp    *net.UDPConn
	closed bool
	born   time.Time
	used   time.Time

	// phone → target
	next     uint64
	ooo      map[uint64][]byte
	queue    [][]byte
	buffered int
	finAt    int64 // -1 until FIN
	inDone   bool  // everything up to FIN delivered and target half-closed
	consumed uint64
	ackLimit uint64 // limit in the last ACK sent
	ackDue   bool
	ackAt    time.Time
	dgramIDs []uint32
	early    [][]byte // datagrams that arrived before the UDP socket was ready

	// target → phone
	sendSeq   uint64
	peerLimit uint64
	peerNext  uint64    // first byte the phone is still missing (from its ACKs)
	oppAt     time.Time // last opportunistic search
	out       []*outFrame
	outBytes  int
	inRR      bool
	finSent   bool
	dgramNext uint32
}

func (s *session) newFlow(id uint32) *flow {
	f := &flow{id: id, sess: s, ooo: map[uint64][]byte{}, finAt: -1, born: time.Now(), used: time.Now(),
		ackLimit: recvWindow, ackAt: time.Now()}
	f.rcond = sync.NewCond(&s.mu)
	f.wcond = sync.NewCond(&s.mu)
	s.flows[id] = f
	return f
}

// lookup returns the flow, creating a placeholder for DATA/FIN that beat their OPEN.
func (s *session) lookup(id uint32) *flow {
	if f := s.flows[id]; f != nil {
		return f
	}
	if _, gone := s.closed[id]; gone {
		return nil
	}
	if len(s.flows) >= maxFlows {
		return nil
	}
	return s.newFlow(id)
}

// ackFrame builds an ACK if the phone may send more than it last heard.
func (f *flow) ackFrame() *outFrame {
	if f.closed || !f.opened {
		return nil
	}
	limit := f.consumed + recvWindow
	f.ackDue = false
	f.ackAt = time.Now()
	f.ackLimit = limit
	// next = first byte still missing (lets the sender resend exactly what blocks us).
	return &outFrame{typ: fAck, payload: put64(put64(put32(nil, f.id), f.next), limit)}
}

func (s *session) consumedLocked(f *flow, n int) {
	f.consumed += uint64(n)
	f.buffered -= n
	f.used = time.Now()
	if f.consumed+recvWindow-f.ackLimit >= recvWindow/4 {
		if a := f.ackFrame(); a != nil {
			s.queueCtrl(a)
		}
	} else {
		f.ackDue = true
	}
}

// ---------------------------------------------------------------- OPEN

func (s *session) onOpen(p []byte) []func() {
	if len(p) < 6 {
		return nil
	}
	id := be32(p)
	proto := p[4]
	atyp := p[5]
	rest := p[6:]
	var host string
	switch atyp {
	case 1:
		if len(rest) < 4 {
			return nil
		}
		host = netip.AddrFrom4([4]byte(rest[:4])).String()
		rest = rest[4:]
	case 4:
		if len(rest) < 16 {
			return nil
		}
		host = netip.AddrFrom16([16]byte(rest[:16])).String()
		rest = rest[16:]
	case 3:
		if len(rest) < 1 || len(rest) < 1+int(rest[0]) {
			return nil
		}
		host = string(rest[1 : 1+int(rest[0])])
		rest = rest[1+int(rest[0]):]
	default:
		return nil
	}
	if len(rest) < 6 {
		return nil
	}
	port := int(rest[0])<<8 | int(rest[1])
	window := uint64(be32(rest[2:]))

	f := s.lookup(id)
	if f == nil || f.opened {
		return nil // duplicate (resent) or already finished
	}
	f.opened = true
	f.proto = proto
	f.peerLimit = window
	addr := net.JoinHostPort(host, strconv.Itoa(port))
	switch proto {
	case protoTCP:
		return []func(){func() { s.dialTCP(f, host, addr) }}
	case protoUDP:
		return []func(){func() { s.dialUDP(f, host, addr) }}
	case protoSpeedDown:
		go s.speedSource(f)
		s.flushInbound(f)
	case protoSpeedUp:
		s.flushInbound(f)
	default:
		return s.closeFlowLocked(f, true, rstNotAllowed)
	}
	return nil
}

// blocked: never reach the server itself, the cloud metadata service or private networks.
var testAllowLoopback = false

func blocked(ip netip.Addr) bool {
	ip = ip.Unmap()
	if testAllowLoopback && ip.IsLoopback() {
		return false
	}
	return ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsPrivate() ||
		ip.IsUnspecified() || ip.IsMulticast() ||
		netip.MustParsePrefix("100.64.0.0/10").Contains(ip) // carrier-grade NAT / cloud internals
}

func resolve(ctx context.Context, host string) (netip.Addr, int) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return ip, 0
	}
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil || len(ips) == 0 {
		return netip.Addr{}, rstNoName
	}
	for _, ip := range ips { // prefer IPv4: most cloud servers have no IPv6 route
		if ip.Unmap().Is4() {
			return ip.Unmap(), 0
		}
	}
	return ips[0], 0
}

func (s *session) dialTCP(f *flow, host, addr string) {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
		defer cancel()
		ip, code := resolve(ctx, host)
		if code == 0 && blocked(ip) {
			code = rstNotAllowed
		}
		var c net.Conn
		if code == 0 {
			_, port, _ := net.SplitHostPort(addr)
			var err error
			c, err = (&net.Dialer{KeepAlive: 30 * time.Second}).DialContext(ctx, "tcp", net.JoinHostPort(ip.String(), port))
			if err != nil {
				code = dialCode(err)
			}
		}
		s.mu.Lock()
		if code != 0 || f.closed {
			after := s.closeFlowLocked(f, code != 0, byte(code))
			s.mu.Unlock()
			if c != nil {
				_ = c.Close()
			}
			run(after)
			return
		}
		if tc, ok := c.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
		}
		f.conn = c
		s.mu.Unlock()
		go s.targetWriter(f)
		s.sourceReader(f, c)
	}()
}

func dialCode(err error) int {
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return rstTimeout
	}
	if errors.Is(err, syscall.ECONNREFUSED) {
		return rstRefused
	}
	return rstUnreachable
}

func (s *session) dialUDP(f *flow, host, addr string) {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
		defer cancel()
		ip, code := resolve(ctx, host)
		if code == 0 && blocked(ip) {
			code = rstNotAllowed
		}
		var c *net.UDPConn
		if code == 0 {
			_, port, _ := net.SplitHostPort(addr)
			ua, err := net.ResolveUDPAddr("udp", net.JoinHostPort(ip.String(), port))
			if err == nil {
				c, err = net.DialUDP("udp", nil, ua)
			}
			if err != nil {
				code = rstUnreachable
			}
		}
		s.mu.Lock()
		if code != 0 || f.closed {
			after := s.closeFlowLocked(f, code != 0, byte(code))
			s.mu.Unlock()
			if c != nil {
				_ = c.Close()
			}
			run(after)
			return
		}
		f.udp = c
		early := f.early
		f.early = nil
		s.mu.Unlock()
		for _, d := range early {
			_, _ = c.Write(d)
		}
		buf := make([]byte, 65535)
		for {
			n, err := c.Read(buf)
			if err != nil {
				s.mu.Lock()
				after := s.closeFlowLocked(f, !f.closed, rstClosed)
				s.mu.Unlock()
				run(after)
				return
			}
			s.mu.Lock()
			if f.closed {
				s.mu.Unlock()
				return
			}
			f.used = time.Now()
			f.dgramNext++
			s.sendDgramLocked(f, f.dgramNext, buf[:n])
			s.mu.Unlock()
		}
	}()
}

// sendDgramLocked: lowest-latency subflow; DNS answers go once per link for reliability.
func (s *session) sendDgramLocked(f *flow, id uint32, data []byte) {
	fr := &outFrame{typ: fDgram, payload: append(put32(put32(nil, f.id), id), data...)}
	dual := f.dualDNS()
	best := map[byte]*subflow{}
	var top *subflow
	for _, sf := range s.subs {
		if !s.usable(sf) {
			continue
		}
		if b := best[sf.link]; b == nil || sf.est(len(data)) < b.est(len(data)) {
			best[sf.link] = sf
		}
		if top == nil || sf.est(len(data)) < top.est(len(data)) {
			top = sf
		}
	}
	if dual {
		for _, sf := range best {
			sf.ctrl = append(sf.ctrl, fr)
		}
	} else if top != nil {
		top.ctrl = append(top.ctrl, fr)
	}
	s.sched.Broadcast()
}

func (f *flow) dualDNS() bool { return f.proto == protoUDP && f.port() == 53 }

func (f *flow) port() int {
	if f.udp != nil {
		if a, ok := f.udp.RemoteAddr().(*net.UDPAddr); ok {
			return a.Port
		}
	}
	return 0
}

// ---------------------------------------------------------------- phone → target

func (s *session) onData(p []byte) []func() {
	if len(p) < 12 {
		return nil
	}
	f := s.lookup(be32(p))
	if f == nil || f.closed {
		return nil
	}
	seq := be64(p[4:])
	data := p[12:]
	end := seq + uint64(len(data))
	if end <= f.next {
		return nil // duplicate
	}
	if seq < f.next { // overlapping resend
		data = data[f.next-seq:]
		seq = f.next
	}
	if seq > f.next {
		if _, dup := f.ooo[seq]; !dup && f.buffered+len(data) <= recvWindow+chunkSize {
			f.ooo[seq] = append([]byte(nil), data...)
			f.buffered += len(data)
		}
		return nil
	}
	f.queue = append(f.queue, append([]byte(nil), data...))
	f.buffered += len(data)
	f.next = end
	for {
		d, ok := f.ooo[f.next]
		if !ok {
			break
		}
		delete(f.ooo, f.next)
		f.queue = append(f.queue, d)
		f.next += uint64(len(d))
	}
	s.flushInbound(f)
	return nil
}

func (s *session) onFin(p []byte) {
	if len(p) < 12 {
		return
	}
	f := s.lookup(be32(p))
	if f == nil || f.closed {
		return
	}
	f.finAt = int64(be64(p[4:]))
	s.flushInbound(f)
}

// flushInbound: speed-up flows consume at once; others wake their writer.
func (s *session) flushInbound(f *flow) {
	if f.proto == protoSpeedUp && f.opened {
		for _, d := range f.queue {
			s.consumedLocked(f, len(d))
		}
		f.queue = nil
		return
	}
	f.wcond.Broadcast()
}

func (s *session) targetWriter(f *flow) {
	s.mu.Lock()
	for {
		for len(f.queue) == 0 && !f.closed && !(f.finAt >= 0 && f.next >= uint64(f.finAt)) {
			f.wcond.Wait()
		}
		if f.closed {
			s.mu.Unlock()
			return
		}
		if len(f.queue) == 0 { // everything up to FIN is written: half-close
			f.inDone = true
			c := f.conn
			after := s.maybeDoneLocked(f)
			s.mu.Unlock()
			if tc, ok := c.(*net.TCPConn); ok {
				_ = tc.CloseWrite()
			}
			run(after)
			return
		}
		d := f.queue[0]
		f.queue = f.queue[1:]
		c := f.conn
		s.mu.Unlock()
		_, err := c.Write(d)
		s.mu.Lock()
		if err != nil {
			after := s.closeFlowLocked(f, true, rstReset)
			s.mu.Unlock()
			run(after)
			return
		}
		s.consumedLocked(f, len(d))
	}
}

func (s *session) onAck(p []byte) {
	if len(p) < 20 {
		return
	}
	f := s.flows[be32(p)]
	if f == nil {
		return
	}
	if n := be64(p[4:]); n > f.peerNext {
		f.peerNext = n
	}
	if l := be64(p[12:]); l > f.peerLimit {
		f.peerLimit = l
		f.rcond.Broadcast()
	}
}

func (s *session) onDgram(p []byte) []func() {
	if len(p) < 8 {
		return nil
	}
	// A datagram can overtake its OPEN (they may travel on different links): hold it.
	f := s.lookup(be32(p))
	if f == nil || f.closed || (f.opened && f.proto != protoUDP) {
		return nil
	}
	id := be32(p[4:])
	for _, x := range f.dgramIDs {
		if x == id {
			return nil // second copy (DNS is sent over both links)
		}
	}
	f.dgramIDs = append(f.dgramIDs, id)
	if len(f.dgramIDs) > 64 {
		f.dgramIDs = f.dgramIDs[len(f.dgramIDs)-64:]
	}
	f.used = time.Now()
	data := append([]byte(nil), p[8:]...)
	if f.udp == nil {
		if len(f.early) < 16 {
			f.early = append(f.early, data)
		}
		return nil
	}
	c := f.udp
	return []func(){func() { _, _ = c.Write(data) }}
}

// ---------------------------------------------------------------- target → phone

// sourceReader reads from the internet side and queues DATA, respecting the
// phone's window and the per-flow queue limit.
func (s *session) sourceReader(f *flow, c net.Conn) {
	buf := make([]byte, chunkSize)
	for {
		s.mu.Lock()
		room := s.waitRoomLocked(f)
		s.mu.Unlock()
		if room <= 0 {
			return
		}
		n, err := c.Read(buf[:room])
		s.mu.Lock()
		if f.closed {
			s.mu.Unlock()
			return
		}
		if n > 0 {
			s.queueDataLocked(f, buf[:n])
		}
		if err != nil {
			var after []func()
			if err == io.EOF {
				f.finSent = true
				s.queueCtrl(&outFrame{typ: fFin, payload: put64(put32(nil, f.id), f.sendSeq)})
				after = s.maybeDoneLocked(f)
			} else {
				after = s.closeFlowLocked(f, true, rstReset)
			}
			s.mu.Unlock()
			run(after)
			return
		}
		s.mu.Unlock()
	}
}

// waitRoomLocked blocks until the flow may queue more data; returns how much (0 = closed).
func (s *session) waitRoomLocked(f *flow) int {
	for {
		if f.closed {
			return 0
		}
		win := int64(f.peerLimit) - int64(f.sendSeq)
		if win > 0 && f.outBytes < outCap {
			if win > chunkSize {
				win = chunkSize
			}
			return int(win)
		}
		f.rcond.Wait()
	}
}

func (s *session) queueDataLocked(f *flow, data []byte) {
	fr := &outFrame{typ: fData, flow: f, payload: append(put64(put32(make([]byte, 0, 12+len(data)), f.id), f.sendSeq), data...)}
	f.sendSeq += uint64(len(data))
	f.used = time.Now()
	s.queueData(f, fr)
}

var zeros = make([]byte, chunkSize)

func (s *session) speedSource(f *flow) {
	for {
		s.mu.Lock()
		room := s.waitRoomLocked(f)
		if room <= 0 {
			s.mu.Unlock()
			return
		}
		s.queueDataLocked(f, zeros[:room])
		s.mu.Unlock()
	}
}

// ---------------------------------------------------------------- closing

// maybeDoneLocked ends a TCP flow once both directions finished.
func (s *session) maybeDoneLocked(f *flow) []func() {
	if f.inDone && f.finSent {
		return s.closeFlowLocked(f, false, 0)
	}
	return nil
}

// closeFlowLocked ends a flow; with rst it tells the phone why.
func (s *session) closeFlowLocked(f *flow, rst bool, code byte) []func() {
	if f.closed {
		return nil
	}
	f.closed = true
	delete(s.flows, f.id)
	s.closed[f.id] = time.Now()
	// A normal close still sends what is queued; a reset drops it.
	if rst {
		s.dropOutLocked(f)
	}
	f.queue = nil
	f.ooo = nil
	if rst {
		s.queueCtrl(&outFrame{typ: fRst, payload: append(put32(nil, f.id), code)})
	}
	f.rcond.Broadcast()
	f.wcond.Broadcast()
	conn, udp := f.conn, f.udp
	return []func(){func() {
		if conn != nil {
			_ = conn.Close()
		}
		if udp != nil {
			_ = udp.Close()
		}
	}}
}

// idleCheckLocked closes forgotten flows: idle UDP, never-opened placeholders.
func (s *session) idleCheckLocked(f *flow, now time.Time) []func() {
	idle := now.Sub(f.used)
	switch {
	case !f.opened && now.Sub(f.born) > 30*time.Second:
		return s.closeFlowLocked(f, false, 0)
	case f.proto == protoUDP && f.dualDNS() && idle > 20*time.Second:
		return s.closeFlowLocked(f, true, rstClosed)
	case f.proto == protoUDP && idle > 120*time.Second:
		return s.closeFlowLocked(f, true, rstClosed)
	}
	return nil
}

// dropOutLocked discards DATA not yet sent (the phone reset the flow, or we did).
func (s *session) dropOutLocked(f *flow) {
	if f.inRR {
		for i, x := range s.rr {
			if x == f {
				s.rr = append(s.rr[:i], s.rr[i+1:]...)
				break
			}
		}
		f.inRR = false
	}
	s.queued -= f.outBytes
	f.out = nil
	f.outBytes = 0
}

func run(fns []func()) {
	for _, fn := range fns {
		fn()
	}
}
