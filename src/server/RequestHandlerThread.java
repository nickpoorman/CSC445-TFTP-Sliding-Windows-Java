package server;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import tftp.AckPacket;
import tftp.DataPacket;
import tftp.ReadRequestPacket;
import tftp.TFTPInterface;
import tftp.TFTPPacket;

public class RequestHandlerThread extends Thread {

	private final ReadRequestPacket packet;
	private boolean slidingWindow;
	private final boolean useDropSim;

	private final static boolean DEBUG = false;
	volatile URL url;
	volatile PageFetcherManager pageFetcherManager;

	public RequestHandlerThread(ReadRequestPacket packet, boolean slidingWindow, boolean useDropSim) {
		this.packet = packet;
		this.slidingWindow = slidingWindow;
		this.useDropSim = useDropSim;
	}

	public void run() {
		// get the name of the url they are looking for
		String pURL = packet.getUrl();

		// create a URL object from the String url
		try {
			this.url = new URL(pURL);
		} catch (MalformedURLException e) {
			System.out.println("Not a valid url: " + pURL);
			return;
		}
		// fetch the page //BufferedInputStream wouldn't block if id didn't get
		// the length so i had to do some hacking
		BufferedInputStream bis = null;
		try {
			bis = new BufferedInputStream(this.url.openConnection().getInputStream());
		} catch (IllegalArgumentException e) {
		} catch (IOException e) {
			System.out.println("Did not get the page for some reason.");
			return;
		}

		// Create a new TFTPInterface here to use from now on
		TFTPInterface tftp = null;
		int numSRetries = 0;
		while (tftp == null) {
			try {
				tftp = new TFTPInterface();
			} catch (SocketException e) {
				// the socket was in use or something
				// e.printStackTrace();
				// try to get a new socket
				if (numSRetries >= 10) {
					// we exceeded the number of retries
					System.out.println("Exceeded the number of socket retries");
					return;
				}
			}
		}

		// going to store it into an array list, note: should be put into a
		// RandomAccessFile or something if its really big
		ArrayList<byte[]> payloads = new ArrayList<byte[]>();

		// read in the bytes from the file into payloads
		boolean done = false;
		// used for debuging numPackets
		int numPackets = 0;
		while (!done) {
			numPackets++;

			// for some reason (probably because the website is being fetched
			// via a tcp socket) the stream doesn't
			// seem to block until the amount of data I have requested is ready,
			// so I created my own that begins
			// filling up a byte buffer until all the data is there that I
			// requested or end of stream
			ByteBuffer bbuf = ByteBuffer.allocate(DataPacket.MAX_DATA_LENGTH);
			while (bbuf.hasRemaining()) {
				byte[] b = new byte[1];
				try {
					int result = bis.read(b);
					if (result == -1) {
						done = true;
						break;
					} else {
						bbuf.put(b);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			// if the last packet has less then 512
			if (((bbuf.capacity() - bbuf.remaining()) > 0) && ((bbuf.capacity() - bbuf.remaining()) < DataPacket.MAX_DATA_LENGTH)) {
				// System.out.println("Found a packet less then 512, total read: "
				// + (bbuf.capacity() - bbuf.remaining()));

				// copy the current payload into a smaller payload
				byte[] tmpPayload = new byte[(bbuf.capacity() - bbuf.remaining())];
				bbuf.rewind();
				bbuf.get(tmpPayload);
				// now set it to be payload
				payloads.add(tmpPayload);
				continue;
			}
			byte[] payload = new byte[DataPacket.MAX_DATA_LENGTH];
			bbuf.rewind();
			bbuf.get(payload);
			payloads.add(payload);
		}

		// get the clients information to send them back the data
		int clientPort = packet.getPort();
		InetAddress clientAddress = packet.getAddress();

		// once we have the file send back the first data packet for the file

		// do sliding window stuff here or do TFTP spec stuff here

		if (slidingWindow) {
			System.out.println("Number of payloads: " + payloads.size());

			// number of retries
			int retries = 0;

			// starting over
			int packetIndex = 0;
			int windowSize = 4;

			// if the window is the last window the keep track of the last
			// packet blockNumber and that its on the last window
			int lastPacketBlockNumber = -1;
			boolean lastWindow = false;

			while (packetIndex < payloads.size()) {
				// send the number of packets
				for (int i = 0; i < windowSize; i++) {
					// if we are at the end of the packets (payloads)
					if (((packetIndex * windowSize) + i) >= payloads.size()) {
						// increment retries because it will come back up here
						// only when a socket is timing out at the end
						retries++;
						if (lastPacketBlockNumber == -1) {
							System.out.println("Sending the last packet");
							lastWindow = true;
							lastPacketBlockNumber = (i - 1);
						}
						break;
					}
					DataPacket returnPacket = new DataPacket(clientAddress, clientPort, DataPacket.TYPE, i, payloads.get((packetIndex * windowSize) + i));
					try {
						System.out.println("Sending packet: " + i);
						tftp.send(returnPacket, this.useDropSim);
						System.out.println("Sent packet");
					} catch (IOException e) {
						// there was some IO problem and the packet didn't send
						e.printStackTrace();
					}
				}

				// if we sent the whole window we still need to know the last
				// block number
				if ((packetIndex * windowSize) >= payloads.size()) {
					if (lastPacketBlockNumber == -1) {
						lastWindow = true;
						lastPacketBlockNumber = (windowSize - 1);
					}
				}

				// wait for an ack thats equal to the number of packets we sent
				try {
					System.out.println("Waiting for an ack");
					TFTPPacket aPacket = (AckPacket) tftp.receive(3000);
					System.out.println("Got an ack");

					if (aPacket.getType() == AckPacket.TYPE) {
						AckPacket ack = (AckPacket) aPacket;
						int blockNumber = ack.getBlockNumber();
						if ((blockNumber == (windowSize - 1)) || (lastWindow && (lastPacketBlockNumber == blockNumber))) {
							packetIndex++;
							if (lastWindow && (lastPacketBlockNumber == blockNumber)) {
								// we need to break out of the loop
								break;
							}
						}
					}
				} catch (SocketTimeoutException e) {
					System.out.println("Socket timed out");
					// It timed out don't do anything, it will send again

					if (retries >= 6) {
						// quit sending it again
						break;
					}
					// e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}

			}
		} else {
			// dont use sliding window
			// keep track of the number of retries
			int retries = 0;
			// int reset = 0;

			// holds the current block number, so we know the offset to read
			// from
			for (int blockNumber = 0; blockNumber < payloads.size();) {
				// create the data packet to send back
				DataPacket returnPacket = new DataPacket(clientAddress, clientPort, DataPacket.TYPE, blockNumber, payloads.get(blockNumber));

				byte[] tmp = payloads.get(blockNumber);
				// now send the packet back
				try {
					System.out.println("Sending packet with block number: " + blockNumber);
					tftp.send(returnPacket, this.useDropSim);
				} catch (IOException e) {
					// there was some IO problem and the packet didn't send
					e.printStackTrace();
				}

				// when we get the ack for the file send the next data packet
				// based on the ack blockNumber
				TFTPPacket aPacket = null;
				try {
					aPacket = (AckPacket) tftp.receive(3000);
				} catch (SocketTimeoutException e) {
					System.out.println("Socket timed out");
					// It timed out don't do anything, it will send again
					retries++;
					if (retries >= 10) {
						// quit sending it again
						System.out.println("Client not responding");
						break;
					}
					// e.printStackTrace();
				} catch (IOException e) {
					// for some reason we could not receive a packet, IO problem
					e.printStackTrace();
				}
				if (aPacket != null) {
					if (aPacket.getType() == AckPacket.TYPE) {
						AckPacket ack = (AckPacket) aPacket;
						blockNumber = ack.getBlockNumber() + 1;
						retries = 0;
					} else {
						System.out.println("We did not get an ack back like we were supposed to");
						// TODO: should probably check to see if its and error
						// packet and do something
						retries++;
						if (retries >= 10) {
							// System.out.println("Exceeded the number of retries");
							break;
						}
					}
				}
			}
		}
		System.out.println("Finished sending the data");
		// we are out of the loop so the file must have been sent or it timed
		// out so we can just clean up
		tftp.destroy();
	}
}
