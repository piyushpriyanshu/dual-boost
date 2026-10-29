package main

import (
	"bufio"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"time"
)

// Frame types (see PROTOCOL.md).
const (
	fHello   = 0x01
	fWelcome = 0x02
	fOpen    = 0x10
	fData    = 0x12
	fAck     = 0x13
	fFin     = 0x14
	fRst     = 0x15
	fDgram   = 0x16
	fPing    = 0x20
	fPong    = 0x21
	fSubAck  = 0x22
	fGone    = 0x23
	fStandby = 0x24
)

const (
	protoTCP       = 1
	protoUDP       = 2
	protoSpeedDown = 3
	protoSpeedUp   = 4
)

const (
	rstRefused     = 1
	rstUnreachable = 2
	rstTimeout     = 3
	rstNoName      = 4
	rstReset       = 5
	rstNotAllowed  = 6
	rstClosed      = 7
)

const (
	maxPlain   = 16400
	chunkSize  = 16 * 1024
	magic      = "DBB1"
	protoVer   = 1
	recordHead = 2
)

// secureConn is one subflow's encrypted record stream.
type secureConn struct {
	c        net.Conn
	br       *bufio.Reader
	bw       *bufio.Writer
	rd, wr   cipher.AEAD
	rCtr     uint64
	wCtr     uint64
	rbuf     []byte
	wbuf     []byte
	received uint64 // record bytes read (for SUBACK)
	written  uint64 // record bytes written
}

func derive(key []byte, cn, sn []byte) (c2s, s2c []byte) {
	m := hmac.New(sha256.New, key)
	m.Write([]byte("dualboost v1"))
	m.Write(cn)
	m.Write(sn)
	prk := m.Sum(nil)
	h := func(label string) []byte {
		x := hmac.New(sha256.New, prk)
		x.Write([]byte(label))
		return x.Sum(nil)
	}
	return h("c2s"), h("s2c")
}

func gcm(k []byte) cipher.AEAD {
	b, err := aes.NewCipher(k)
	if err != nil {
		panic(err)
	}
	a, err := cipher.NewGCM(b)
	if err != nil {
		panic(err)
	}
	return a
}

// serverHandshake runs the key exchange on an accepted connection.
func serverHandshake(c net.Conn, key []byte) (*secureConn, error) {
	_ = c.SetDeadline(time.Now().Add(handshakeTime))
	hello := make([]byte, 4+32)
	if _, err := io.ReadFull(c, hello); err != nil {
		return nil, err
	}
	if string(hello[:4]) != magic {
		return nil, errors.New("bad magic")
	}
	sn := make([]byte, 32)
	if _, err := rand.Read(sn); err != nil {
		return nil, err
	}
	if _, err := c.Write(sn); err != nil {
		return nil, err
	}
	c2s, s2c := derive(key, hello[4:], sn)
	sc := newSecure(c, gcm(c2s), gcm(s2c))
	return sc, nil
}

// clientHandshake is used by tests (the phone has its own implementation).
func clientHandshake(c net.Conn, key []byte) (*secureConn, error) {
	cn := make([]byte, 32)
	if _, err := rand.Read(cn); err != nil {
		return nil, err
	}
	if _, err := c.Write(append([]byte(magic), cn...)); err != nil {
		return nil, err
	}
	sn := make([]byte, 32)
	if _, err := io.ReadFull(c, sn); err != nil {
		return nil, err
	}
	c2s, s2c := derive(key, cn, sn)
	return newSecure(c, gcm(s2c), gcm(c2s)), nil
}

func newSecure(c net.Conn, rd, wr cipher.AEAD) *secureConn {
	return &secureConn{
		c: c, br: bufio.NewReaderSize(c, 64*1024), bw: bufio.NewWriterSize(c, 64*1024),
		rd: rd, wr: wr, rbuf: make([]byte, maxPlain+64), wbuf: make([]byte, 0, maxPlain+64),
	}
}

func nonce(n uint64) []byte {
	b := make([]byte, 12)
	binary.BigEndian.PutUint64(b[4:], n)
	return b
}

// readFrame returns the frame type and payload. The payload is valid until the next call.
func (s *secureConn) readFrame() (byte, []byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(s.br, hdr[:]); err != nil {
		return 0, nil, err
	}
	n := int(binary.BigEndian.Uint16(hdr[:]))
	if n < 17 || n > maxPlain+16 {
		return 0, nil, errors.New("bad record length")
	}
	ct := s.rbuf[:n]
	if _, err := io.ReadFull(s.br, ct); err != nil {
		return 0, nil, err
	}
	pt, err := s.rd.Open(ct[:0], nonce(s.rCtr), ct, nil)
	if err != nil {
		return 0, nil, errors.New("decrypt failed")
	}
	s.rCtr++
	s.received += uint64(recordHead + n)
	if len(pt) < 1 {
		return 0, nil, errors.New("empty frame")
	}
	return pt[0], pt[1:], nil
}

// writeFrame buffers one record and returns its size on the wire. Call flush to send.
func (s *secureConn) writeFrame(typ byte, payload []byte) (int, error) {
	if len(payload)+1 > maxPlain {
		return 0, errors.New("frame too large")
	}
	pt := append(s.wbuf[:0], typ)
	pt = append(pt, payload...)
	ct := s.wr.Seal(pt[:0], nonce(s.wCtr), pt, nil) // in place: wbuf has room for the tag
	s.wbuf = ct[:0]
	s.wCtr++
	var hdr [2]byte
	binary.BigEndian.PutUint16(hdr[:], uint16(len(ct)))
	if _, err := s.bw.Write(hdr[:]); err != nil {
		return 0, err
	}
	if _, err := s.bw.Write(ct); err != nil {
		return 0, err
	}
	n := recordHead + len(ct)
	s.written += uint64(n)
	return n, nil
}

func (s *secureConn) flush() error { return s.bw.Flush() }

// ---- small encoding helpers

func be32(b []byte) uint32 { return binary.BigEndian.Uint32(b) }
func be64(b []byte) uint64 { return binary.BigEndian.Uint64(b) }

func put32(b []byte, v uint32) []byte { return binary.BigEndian.AppendUint32(b, v) }
func put64(b []byte, v uint64) []byte { return binary.BigEndian.AppendUint64(b, v) }
