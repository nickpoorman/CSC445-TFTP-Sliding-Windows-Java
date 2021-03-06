#CSC445 TFTP Sliding Windows Java Implementation#
   **By:** Nicholas Poorman
   **Date:** March 31, 2010
   **Class:** CSC445 - Networking
   **Project:** 2 - http://gee.cs.oswego.edu/dl/csc445/a2.html

---

Assignment 2
Write a proxy server program, that relays files/pages. To demonstrate, you'll need a client and a server program:

    1. The proxy server awaits connections.
    2. A client connects, sends a URL.
    3. The proxy server gets the corresponding page/file using HTTP.
    4. The proxy sends the page/file to the client, that then uses (stores or displays) it. 

The proxy server is allowed to, but not required to, cache pages.

Wherever applicable, use the commands and protocol for TFTP (IETF RFC 1350), with the following modifications:

    1. Use URLs, not file names
    2. Support only client "read" (download) requests, not writes.
    3. Support only binary (octet) transmission.
    4. Support a command line argument specifiying whether packets are IPv4 vs IPv6 UDP datagrams
    5. Support a command line argument specifying to use TCP-style sliding windows rather than the sequential acks used in TFTP. To implement this, you may need to design and use additional packet header information than that in TFTP.
    6. Support a command line argument controlling whether to pretend to drop 1 percent of the packets; 

Create a web page showing throughput across varying conditions (V4 vs V6; sequential vs windowed acks; drops vs no drops)
Doug Lea

---
