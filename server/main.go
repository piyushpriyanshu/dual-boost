// DualBoost bond server: joins the phone's Wi-Fi and mobile connections into one.
package main

import (
	"encoding/base64"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

var (
	version = "dev"
	debug   = false
)

const (
	maxFlows    = 8192
	maxSessions = 16
)

type server struct {
	key      []byte
	mu       sync.Mutex
	sessions map[[16]byte]*session
	pending  chan struct{}  // handshakes in progress (bounded: strangers can't exhaust memory)
	perIP    map[string]int // handshakes in progress per address
}

const (
	maxPending      = 256
	maxPendingPerIP = 16
	handshakeTime   = 4 * time.Second
)

func (s *server) drop(sess *session) {
	s.mu.Lock()
	if s.sessions[sess.id] == sess {
		delete(s.sessions, sess.id)
	}
	s.mu.Unlock()
}

func loadKey(path string) ([]byte, error) {
	if path == "" {
		if d := os.Getenv("CREDENTIALS_DIRECTORY"); d != "" {
			path = filepath.Join(d, "key")
		}
	}
	var txt string
	if path != "" {
		b, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		txt = string(b)
	} else {
		txt = os.Getenv("DUALBOOST_KEY")
	}
	txt = strings.TrimSpace(txt)
	k, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(txt, "="))
	if err != nil || len(k) != 32 {
		return nil, fmt.Errorf("the key must be 32 bytes in base64url (43 characters)")
	}
	return k, nil
}

func main() {
	listen := flag.String("listen", ":443", "address to listen on")
	keyFile := flag.String("key-file", "", "file with the base64url key (default: systemd credential 'key' or $DUALBOOST_KEY)")
	showVer := flag.Bool("version", false, "print the version")
	flag.BoolVar(&debug, "debug", false, "log subflow events")
	flag.BoolVar(&testAllowLoopback, "test-allow-loopback", false, "testing only: allow flows to 127.0.0.1")
	flag.Parse()
	if *showVer {
		fmt.Println(version)
		return
	}
	key, err := loadKey(*keyFile)
	if err != nil {
		log.Fatalf("key: %v", err)
	}
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	log.Printf("DualBoost bond server %s listening on %s", version, ln.Addr())
	srv := newServer(key)
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			time.Sleep(50 * time.Millisecond)
			continue
		}
		go srv.serve(c)
	}
}

func newServer(key []byte) *server {
	return &server{key: key, sessions: map[[16]byte]*session{}, pending: make(chan struct{}, maxPending), perIP: map[string]int{}}
}

// admit limits handshakes that have not proven the key yet; the returned func releases the slot.
func (s *server) admit(c net.Conn) (func(), bool) {
	host, _, _ := net.SplitHostPort(c.RemoteAddr().String())
	select {
	case s.pending <- struct{}{}:
	default:
		return nil, false
	}
	s.mu.Lock()
	if s.perIP[host] >= maxPendingPerIP {
		s.mu.Unlock()
		<-s.pending
		return nil, false
	}
	s.perIP[host]++
	s.mu.Unlock()
	return func() {
		s.mu.Lock()
		if s.perIP[host]--; s.perIP[host] <= 0 {
			delete(s.perIP, host)
		}
		s.mu.Unlock()
		<-s.pending
	}, true
}

func (s *server) serve(c net.Conn) {
	release, ok := s.admit(c)
	if !ok {
		_ = c.Close()
		return
	}
	authed := false
	defer func() {
		if !authed {
			release()
		}
	}()
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(15 * time.Second)
	}
	sc, err := serverHandshake(c, s.key)
	if err != nil {
		_ = c.Close()
		return
	}
	typ, p, err := sc.readFrame() // a wrong key fails here: close without a word
	if err != nil || typ != fHello || len(p) < 19 {
		_ = c.Close()
		return
	}
	authed = true
	release()
	var id [16]byte
	copy(id[:], p[:16])
	link, subID, standby := p[16], p[17], p[18] != 0
	_ = c.SetDeadline(time.Time{})

	var sess *session
	var sf *subflow
	status := byte(1)
	for tries := 0; sf == nil; tries++ {
		s.mu.Lock()
		sess = s.sessions[id]
		status = 1
		if sess == nil {
			if len(s.sessions) >= maxSessions {
				s.evictLocked()
			}
			sess = newSession(id, s)
			s.sessions[id] = sess
			status = 0
		}
		s.mu.Unlock()
		if sf = sess.attach(sc, subID, link, standby); sf == nil {
			sess.mu.Lock()
			ended := sess.gone
			sess.mu.Unlock()
			if !ended || tries > 2 {
				_ = c.Close() // too many subflows
				return
			}
			s.drop(sess) // it ended just now: start a fresh one
		}
	}
	// WELCOME after attaching: the session (and its status) is settled.
	if _, err := sc.writeFrame(fWelcome, []byte{status, protoVer}); err != nil {
		sess.kill(sf, "welcome failed")
		return
	}
	if err := sc.flush(); err != nil {
		sess.kill(sf, "welcome failed")
		return
	}
	if debug {
		log.Printf("subflow %d link %d from %s (status %d)", subID, link, c.RemoteAddr(), status)
	}
	go sf.writeLoop()
	sf.readLoop()
}

// evictLocked removes the oldest session without subflows.
func (s *server) evictLocked() {
	var victim *session
	var victimAt time.Time
	for _, x := range s.sessions {
		x.mu.Lock()
		idle := len(x.subs) == 0
		at := x.empty
		x.mu.Unlock()
		if idle && (victim == nil || at.Before(victimAt)) {
			victim, victimAt = x, at
		}
	}
	if victim != nil {
		delete(s.sessions, victim.id)
	}
}
