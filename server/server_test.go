package main

import (
	"bytes"
	"crypto/rand"
	"net"
	"testing"
	"time"
)

// Minimal one-subflow client: proves handshake, OPEN, DATA, ACK, SUBACK and FIN work.
func TestEchoOneSubflow(t *testing.T) {
	debug = true
	key := make([]byte, 32)
	rand.Read(key)
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	srv := &server{key: key, sessions: map[[16]byte]*session{}}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go srv.serve(c)
		}
	}()
	// echo target on loopback is "blocked" by policy, so allow it for this test via a public-looking alias:
	echo, _ := net.Listen("tcp", "127.0.0.1:0")
	go func() {
		c, _ := echo.Accept()
		buf := make([]byte, 65536)
		for {
			n, err := c.Read(buf)
			if n > 0 {
				c.Write(buf[:n])
			}
			if err != nil {
				c.(*net.TCPConn).CloseWrite()
				return
			}
		}
	}()
	testAllowLoopback = true

	c, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	sc, err := clientHandshake(c, key)
	if err != nil {
		t.Fatal(err)
	}
	var sid [16]byte
	rand.Read(sid[:])
	sc.writeFrame(fHello, append(sid[:], 1, 0, 0))
	sc.flush()
	typ, p, err := sc.readFrame()
	if err != nil || typ != fWelcome || p[0] != 0 {
		t.Fatalf("welcome: %v %v %v", typ, p, err)
	}
	port := echo.Addr().(*net.TCPAddr).Port
	open := append(put32(nil, 7), protoTCP, 1, 127, 0, 0, 1, byte(port>>8), byte(port))
	open = put32(open, 8<<20)
	sc.writeFrame(fOpen, open)
	msg := make([]byte, 3<<20)
	rand.Read(msg)
	for off := 0; off < len(msg); off += chunkSize {
		end := min(off+chunkSize, len(msg))
		sc.writeFrame(fData, append(put64(put32(nil, 7), uint64(off)), msg[off:end]...))
	}
	sc.writeFrame(fFin, put64(put32(nil, 7), uint64(len(msg))))
	sc.flush()
	got := make([]byte, 0, len(msg))
	final := -1
	deadline := time.Now().Add(10 * time.Second)
	c.SetReadDeadline(deadline)
	for {
		typ, p, err := sc.readFrame()
		if err != nil {
			t.Fatalf("read: %v (got %d)", err, len(got))
		}
		switch typ {
		case fData:
			if be64(p[4:]) != uint64(len(got)) {
				t.Fatalf("out of order on one subflow: %d vs %d", be64(p[4:]), len(got))
			}
			got = append(got, p[12:]...)
			sc.writeFrame(fSubAck, put64(nil, sc.received))
			sc.writeFrame(fAck, put64(put64(put32(nil, 7), uint64(len(got))), uint64(len(got))+8<<20))
			sc.flush()
		case fFin:
			final = int(be64(p[4:]))
		}
		if final >= 0 && len(got) >= final {
			if !bytes.Equal(got, msg) {
				t.Fatalf("echo mismatch: %d bytes", len(got))
			}
			t.Logf("echoed %d bytes OK", len(got))
			return
		}
		switch typ {
		case fRst:
			t.Fatalf("rst %v", p)
		case fPing:
			sc.writeFrame(fPong, p)
			sc.flush()
		}
	}
}
