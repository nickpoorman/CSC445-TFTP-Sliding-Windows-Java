package client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;

import tftp.AckPacket;
import tftp.DataPacket;
import tftp.ReadRequestPacket;
import tftp.TFTPInterface;
import tftp.TFTPPacket;

public class Client {

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		String usage = "usage: java client.Client 4|6 <remote address> <url> [s] [d]";
		String host = "";
		int version = 0;
		String url = "";
		boolean useSlidingWindow = false;
		boolean dropSim = false;
		if (args.length < 3) {
			System.out.println(usage);
			return;
		} else if (args.length == 3) {
			version = Integer.parseInt(args[0]);
			host = args[1];
			url = args[2];
		} else if (args.length == 4) {
			version = Integer.parseInt(args[0]);
			host = args[1];
			url = args[2];
			if (args[3].equals("s")) {
				useSlidingWindow = true;
			} else if (args[3].equals("d")) {
				dropSim = true;
			} else {
				System.out.println(usage);
				return;
			}
		} else if (args.length == 5) {
			version = Integer.parseInt(args[0]);
			host = args[1];
			url = args[2];
			if (args[3].equals("s")) {
				useSlidingWindow = true;
			} else if (args[3].equals("d")) {
				dropSim = true;
			} else {
				System.out.println(usage);
				return;
			}
			if (args[4].equals("s")) {
				useSlidingWindow = true;
			} else if (args[4].equals("d")) {
				dropSim = true;
			} else {
				System.out.println(usage);
				return;
			}
		}

		InetAddress serverAddress = InetAddress.getByName(host);
		int addressPort = 63010;
		String pageName = "data";
		// String urlRequest = "http://cs.oswego.edu/~poorman/index.html";
		boolean finished = true;

		// File to write to
		File save = new File(pageName);

		// a stream to hold the data
		FileOutputStream fos = new FileOutputStream(save);

		// Create a new TFTPInterface
		TFTPInterface tftp = new TFTPInterface();

		// create the request packet
		ReadRequestPacket requestPacket = new ReadRequestPacket(serverAddress, addressPort, ReadRequestPacket.TYPE, url);

		// send a request
		tftp.send(requestPacket, true);

		// keep track of the last packet size
		int lastDataSize = 0;

		// this will hold the total data size of the file
		int dataSize = 0;

		int numberOfPackets = 0;

		// start the time
		long startTime = System.nanoTime();

		if (useSlidingWindow) {

			int rws = 4;
			ArrayList<DataPacket> advertisedWindow = new ArrayList<DataPacket>();

			int lastDataLength = 0;

			do {
				// get a data packet
				TFTPPacket dPacket = tftp.receive();

				if (dPacket.getType() == DataPacket.TYPE) {

					DataPacket dataPacket = (DataPacket) dPacket;

					// get the packets block number
					int blockNumber = dataPacket.getBlockNumber();

					lastDataLength = dataPacket.getPayloadLength();

					boolean added = false;
					// put the packet in the right spot in a buffer
					for (int i = 0; i < advertisedWindow.size(); i++) {
						if (dataPacket.getBlockNumber() < advertisedWindow.get(i).getBlockNumber()) {
							// put it here
							advertisedWindow.add(i, dataPacket);
							added = true;
							break;
						} else if (dataPacket.getBlockNumber() == advertisedWindow.get(i).getBlockNumber()) {
							// just drop it cause its already in there
							added = true;
							break;
						}
					}
					if (!added) {
						advertisedWindow.add(dataPacket);
					}

					// when the window is full or we are on our last packet
					if ((advertisedWindow.size() == rws) || (lastDataLength < DataPacket.MAX_DATA_LENGTH)) {
						// make sure all the packets before it are there
						if (blockNumber == (advertisedWindow.size() - 1)) {
							// flush it when the window is full
							for (int i = 0; i < advertisedWindow.size(); i++) {
								fos.write(advertisedWindow.get(i).getPayload());
								numberOfPackets++;
								dataSize += advertisedWindow.get(i).getPayloadLength() + 4;
							}

							// now send back an ack
							// create the ack packet
							AckPacket ackPacket = new AckPacket(dataPacket.getAddress(), dataPacket.getPort(), AckPacket.TYPE, blockNumber);

							// send back an ack of the block number
							tftp.send(ackPacket, dropSim);

							if (lastDataLength < DataPacket.MAX_DATA_LENGTH) {
								finished = true;
							}

							// clear the window
							advertisedWindow.clear();
						}
					}
				} else {
					System.out.println("Didn't get a data packet");
				}
			} while (lastDataLength >= DataPacket.MAX_DATA_LENGTH);

		} else {

			// keep track of the next blockNumber we are looking for
			int nextBlockNumber = 0;
			do {
				// get the request response (should be a data packet)
				TFTPPacket dPacket = tftp.receive();

				// save the data if its a data packet
				if (dPacket.getType() == DataPacket.TYPE) {
					DataPacket dataPacket = (DataPacket) dPacket;

					// if the next data packet is the one we are looking for
					if (nextBlockNumber == dataPacket.getBlockNumber()) {
						// set the size of its payload
						lastDataSize = dataPacket.getPayloadLength();

						// write the payload of the packet to the stream
						fos.write(dataPacket.getPayload());
						numberOfPackets++;
						nextBlockNumber++;

						// adjust the total data size received
						dataSize += lastDataSize + 4;
					}

					// if the one they gave us is less then the one we are
					// looking for meaning we already got it
					// just send them back an ack for the one they just sent
					// because the ack must have gotten lost

					// get the return info
					InetAddress returnServerAddress = dataPacket.getAddress();
					int port = dataPacket.getPort();
					int blockNumber = dataPacket.getBlockNumber();

					// create the ack packet
					AckPacket ackPacket = new AckPacket(returnServerAddress, port, AckPacket.TYPE, blockNumber);

					// send back an ack of the block number
					tftp.send(ackPacket, dropSim);

				} else {
					System.out.println("Something went wrong. Supposed to get a data packet but didn't");
					finished = false;
					break;
				}
			} while (lastDataSize >= DataPacket.MAX_DATA_LENGTH);
		}
		if (finished) {
			// flush the stream
			fos.flush();

			// stop the time
			long endTime = System.nanoTime();
			long totalTime = endTime - startTime;

			System.out.println("Done!");
			System.out.println("Total data size received: " + dataSize);
			System.out.println("Total time: " + totalTime);
			// double throughput = (dataSize * 8) / (totalTime / 1000000000);
			double throughput = ((double) (dataSize * 8) / (double) (totalTime / 1000000000.0));
			System.out.println("Throughput (bits/sec): " + throughput);
			System.out.println("Number of packets: " + numberOfPackets);
		} else {
			System.out.println("Didn't finish!");
		}

	}

	public static String generateRandomString() {
		SecureRandom random = new SecureRandom();
		return new BigInteger(130, random).toString(32);
	}
}
