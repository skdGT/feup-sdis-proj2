package peer;

import client.ClientCallbackInterface;
import messages.Message;
import messages.application.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import peer.backend.PeerFile;
import peer.backend.PeerInternalState;
import peer.chord.ChordPeer;
import peer.chord.ChordReference;
import peer.ssl.MessageTimeoutException;
import peer.ssl.SSLConnection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.*;

/**
 * Main class for the System, the peer is responsible for the execution of protocols and
 * processing the client requests. The peer extends a Chord Peer which extends an SSL Peer. And this Peer
 * implements the RemotePeer interface used for the client to submit requests.
 */
public class Peer extends ChordPeer implements RemotePeer {
    private final static Logger log = LogManager.getLogger(Peer.class);
    private final String sap;
    public final ExecutorService PROTOCOL_EXECUTOR = Executors.newFixedThreadPool(16);
    private final ExecutorService clientRequests = Executors.newFixedThreadPool(8);
    private ClientCallbackInterface callbackInterface;

    /**
     * Main method to start the peer
     *
     * @param args Command Line Arguments
     * @throws UnknownHostException On error getting the address passed by command line
     */
    public static void main(String[] args) throws UnknownHostException {
        if (args.length < 3) {
            System.out.println("Usage: java Peer <Service Access Point> <BOOT PEER IP> <BOOT PEER PORT> [-b]");
            System.out.println("Service Access Point: RMI bind");
            return;
        }

        String sap = args[0];
        InetAddress bootAddress = InetAddress.getByName(args[1]);
        int bootPort = Integer.parseInt(args[2]);
        boolean boot = (args.length > 3 && args[3].equals("-b"));

        try {
            Peer peer = new Peer(new InetSocketAddress(bootAddress, bootPort), boot, sap);

            try {
                RemotePeer stub = (RemotePeer) UnicastRemoteObject.exportObject(peer, 0);
                Registry registry = LocateRegistry.getRegistry();
                registry.rebind(peer.getServiceAccessPoint(), stub);
                log.info("[RMI] Registry Complete");
                log.info("[RMI] Service Access Point: " + peer.getServiceAccessPoint());
            } catch (Exception e) {
                log.error("[RMI] Registry Exception: " + e.getCause());
                log.info("[RMI] Will continue but RMI is offline");
            }

            log.info("Peer Initiated");
            new Thread(peer::start).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Peer Constructor
     *
     * @param address Own or boot peer address, depending on the boot flag
     * @param boot    boot flag
     * @param sap     Service Access Point, for the RMI
     * @throws Exception On error creating the peer
     */
    public Peer(InetSocketAddress address, boolean boot, String sap) throws Exception {
        super(address, boot);
        this.sap = sap;
    }

    /**
     * Method to register a client so this peer can send back notifications
     *
     * @param callbackInterface Client Interface (Callback)
     */
    public void register(ClientCallbackInterface callbackInterface) {
        this.callbackInterface = callbackInterface;
    }

    /**
     * Method to send a notification back to the client
     *
     * @param message Message to be sent to client
     */
    private void sendNotification(String message) {
        if (this.callbackInterface != null) {
            try {
                this.callbackInterface.notify(message);
            } catch (RemoteException e) {
                log.error("Caught an exception on RMI");
            }
        }
    }

    /**
     * Method to start a backup operation by the client
     *
     * @param filename          Filename to be backed up
     * @param replicationDegree Desired Replication degree
     */
    @Override
    public void backup(String filename, int replicationDegree) {
        clientRequests.submit(() -> _backup(filename, replicationDegree));
    }

    /**
     * Private method to backup a file, this method generates 3 * <code>replication degree</code> keys to attempt
     * to find <code>replication degree</code> peers to send the file to. For each peer it finds starts a new
     * separate backup operation, this behaviour enhances the concurrency because this means we can send a file to
     * multiple peers at the same time.
     *
     * @param filename          File to be backed up
     * @param replicationDegree desired replication degree
     */
    private void _backup(String filename, int replicationDegree) {
        if (!this.isActive()) {
            sendNotification("Peer's Server is not online yet!");
            return;
        }

        if (this.successor() == null || this.successor().getGuid() == this.guid) {
            sendNotification("Could not start BACKUP as this peer has not found other peers yet");
            return;
        }

        log.info("Starting BACKUP for {} with replication degree: {}", filename, replicationDegree);

        File file = new File(filename);
        BasicFileAttributes attributes;
        try {
            // Assemble the Payload for the BACKUP protocol message
            attributes = Files.getFileAttributeView(file.toPath(), BasicFileAttributeView.class).readAttributes();
            long size = attributes.size();
            String fileId = Utils.generateHashForFile(filename, attributes);


            List<Integer> keys = new ArrayList<>();
            ThreadLocalRandom.current().ints(0, Constants.CHORD_MAX_PEERS).distinct().limit(replicationDegree * 4L).forEach(keys::add);

            log.info("Keys: {}", keys);

            List<ChordReference> targetPeers = new ArrayList<>();
            List<Integer> targetKeys = new ArrayList<>();

            for (int key : keys) {
                ChordReference peer = findSuccessor(key);
                if (peer.getGuid() != this.guid && !targetPeers.contains(peer)) {
                    targetPeers.add(peer);
                    targetKeys.add(key);
                }
                if (targetPeers.size() == replicationDegree) break;
            }

            if (targetPeers.size() == 0) {
                sendNotification("Could not find Peers to Backup this file!");
                return;
            }

            PeerFile peerFile;
            if (this.internalState.getSentFilesMap().containsKey(filename)) {
                peerFile = this.internalState.getSentFilesMap().get(filename);
            } else {
                peerFile = new PeerFile(-1, fileId, this.getReference(), size, replicationDegree);
            }

            log.info("Sending file to: {} with keys: {}", targetPeers, targetKeys);
            List<Future<String>> tasks = new ArrayList<>();

            for (ChordReference targetPeer : targetPeers) {
                String body = String.join("::",
                        Arrays.asList(fileId,
                                String.valueOf(size),
                                this.getReference().toString(),
                                targetKeys.get(targetPeers.indexOf(targetPeer)).toString(),
                                String.valueOf(replicationDegree)
                        ));
                Backup message = new Backup(this.getReference(), body.getBytes(StandardCharsets.UTF_8));

                Callable<String> runnable = () -> backup(targetPeer, file, message, peerFile);
                tasks.add(PROTOCOL_EXECUTOR.submit(runnable));
            }


            StringBuilder result = new StringBuilder("----------------------------------------------------------------\n");
            result.append(String.format("Result for %s with replication degree %d\n", filename, replicationDegree));
            for (Future<String> task : tasks) {
                String peerResult = task.get();
                result.append(peerResult).append("\n");
            }
            result.append("----------------------------------------------------------------");

            this.internalState.addSentFile(filename, peerFile);

            sendNotification(result.toString());
        } catch (IOException e) {
            sendNotification("Failed to BACKUP file: " + e.getMessage());
        } catch (ExecutionException e) {
            sendNotification("Failed to BACKUP file on one Peer: " + e.getMessage());
        } catch (InterruptedException e) {
            sendNotification("Interrupted Exception");
        }
    }

    /**
     * Method to start a backup for a file to a peer. This method sends a BACKUP message, waits for an Acknowledgement
     * from the remote peer, and then proceeds to send the file, after sending the file it waits for another ACK
     * message so it can close the connection.
     *
     * @param target   Target Peer
     * @param file     Target File
     * @param message  Backup Message
     * @param peerFile Peer File
     * @return result of this operation
     */
    public String backup(ChordReference target, File file, Backup message, PeerFile peerFile) {
        try {
            log.info("Starting backup for {} on Peer: {}", file.getName(), target);
            SSLConnection connection = this.connectToPeer(target.getAddress());
            this.send(connection, message);
            log.info("Waiting ACK from Peer: {}...", target);
            Message reply = this.receiveBlocking(connection, 100);

            if (reply instanceof Ack) {
                // continue
            } else if (reply instanceof Nack) {
                this.closeConnection(connection);
                if (((Nack) reply).getMessage().equals("NOSPACE")) {
                    return String.format("Peer %s has no space to store the file", target);
                } else if (((Nack) reply).getMessage().equals("HAVEFILE")) {
                    peerFile.addKey(message.getKey());
                    return String.format("Peer %s already has the file", target);
                } else {
                    return String.format("Received unexpected message from Peer: %s", target);
                }
            } else {
                return String.format("Received unexpected message from Peer: %s", target);
            }

            FileChannel fileChannel = FileChannel.open(file.toPath());
            log.info("Sending file to Peer {}...", target);
            this.sendFile(connection, fileChannel);
            log.info("File sent to Peer {}!", target);

            log.info("Waiting ACK from Peer: {}...", target);
            reply = this.receiveBlocking(connection, 2000);
            if (!(reply instanceof Ack)) return "Failed to receive ACK from peer after sending file";
            log.info("Received ACK from Peer {}!", target);
            this.closeConnection(connection);

            peerFile.addKey(message.getKey());
        } catch (IOException | MessageTimeoutException e) {
            e.printStackTrace();
            return "Failed to Backup file on Peer " + target;
        }

        return "Backup Successful on Peer " + target;
    }

    /**
     * Method to receive a file, the receiving procedure starts by sending a GET message, and then waiting for
     * an acknowledgement signaling if the remote peer has the file and can send it or not, then another GET
     * is sent and this peer starts waiting for the file bytes. After the bytes are received this peer closes
     * the connection to the remote peer.
     *
     * @param connection Connection to be used
     * @param peerFile   Peer File associated
     * @param filename   Filename
     * @return true if the file was successfully received
     */
    public boolean receiveFile(SSLConnection connection, PeerFile peerFile, String filename) {
        log.info("Starting GET...");

        String fileId = peerFile.getId();
        ChordReference owner = peerFile.getOwner();
        long size = peerFile.getSize();
        int key = peerFile.getKey();
        int replicationDegree = peerFile.getReplicationDegree();

        // send GET message for fileID
        this.send(connection, new Get(this.getReference(), peerFile.getId().getBytes(StandardCharsets.UTF_8)));
        log.info("GET sent!");

        // receive Acknowledgement
        Message ack;
        try {
            ack = this.receiveBlocking(connection, 500);
        } catch (MessageTimeoutException e) {
            log.error("Could not receive ACK for GET message on for {}", peerFile);
            return false;
        }
        if (ack instanceof Ack) {
            // proceed
        } else if (ack instanceof Nack) {
            log.error("Not found: {}", peerFile);
            return false;
        }

        // send new GET message, remote peer will start to write file to socket
        this.send(connection, new Get(this.getReference(), peerFile.getId().getBytes(StandardCharsets.UTF_8)));

        log.info("Getting file: {}", peerFile);

        try {
            FileOutputStream outputStream = new FileOutputStream(this.getFileLocation(filename));
            FileChannel fileChannel = outputStream.getChannel();
            log.info("Ready to receive file...");
            connection.setPeerNetData(ByteBuffer.allocate(Constants.TLS_CHUNK_SIZE));
            connection.getSocketChannel().configureBlocking(true);
            this.receiveFile(connection, fileChannel, size);
            fileChannel.close();
            log.info("Received file!");

            this.closeConnection(connection);

            this.addSavedFile(key, fileId, owner, size, replicationDegree);
            return true;
        } catch (IOException e) {
            log.error("Error receiving file: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Method to restore a file, this method is called by the RMI client
     *
     * @param filename Filename for the file to be restored
     * @throws RemoteException on error with RMI
     */
    @Override
    public void restore(String filename) throws RemoteException {
        clientRequests.submit(() -> _restore(filename));
    }

    /**
     * Method to get the file, this method loops the file associated keys, and tries to receive
     * the target file from the peers which are serving it.
     *
     * @param filename Target file's filename
     */
    private void _restore(String filename) {
        log.info("Starting RESTORE protocol for: {}", filename);

        PeerFile peerFile = this.internalState.getSentFilesMap().get(filename);
        if (peerFile == null) {
            sendNotification("File was not backed up: " + filename);
            return;
        }

        String newFilename = "restored_" + new File(filename).getName();

        // send get for each peer, if a nack is received abort and go to the next one
        for (Integer key : peerFile.getKeys()) {
            ChordReference reference = this.findSuccessor(key);
            SSLConnection connection = this.connectToPeer(reference.getAddress());
            if (connection == null) continue;

            boolean result = this.receiveFile(connection, peerFile, newFilename);
            if (result) {
                log.info("Restored file: {} under: {}", filename, newFilename);
                sendNotification("File: " + filename + " restored successfully!");
                return;
            }
        }

        sendNotification("File: " + filename + " could not be restored!");
    }

    /**
     * Method to delete a File, requested by the client
     *
     * @param filename Target File's Filename to be deleted
     * @throws RemoteException on error with RMI
     */
    @Override
    public void delete(String filename) throws RemoteException {
        clientRequests.submit(() -> _delete(filename));
    }

    /**
     * Method to delete a File, this method finds the successor for each associated key
     * with the peer file, and sends a DELETE message for each one.
     *
     * @param filename Target's File Filename to be deleted
     */
    private void _delete(String filename) {
        log.info("Starting DELETE client request for {}", filename);

        PeerFile file = this.internalState.getSentFilesMap().get(filename);

        if (file == null) {
            log.error("File {} is not backed up", filename);
            sendNotification(String.format("File %s was not backed up!", filename));
            return;
        }

        Set<ChordReference> targetPeers = new HashSet<>();

        for (Integer key : file.getKeys()) {
            ChordReference reference = this.findSuccessor(key);
            if (reference == null) continue;
            targetPeers.add(reference);
            Runnable executable = () -> sendDelete(file, reference);
            PROTOCOL_EXECUTOR.submit(executable);
        }

        log.info("All DELETE requests processed!");
        sendNotification(String.format("DELETE for %s was sent to:\n%s", filename, targetPeers));
    }

    /**
     * Method to send a delete Message to a remove peer, related to a peer file
     *
     * @param file      Context File
     * @param reference Target Peer
     */
    private void sendDelete(PeerFile file, ChordReference reference) {
        file.beingDeleted = true;
        SSLConnection connection = this.connectToPeer(reference.getAddress());
        if (connection == null) return;
        this.send(connection, new Delete(this.getReference(), file.getId().getBytes(StandardCharsets.UTF_8)));
        log.info("Sent DELETE to {} for {}", reference, file.getId());
    }

    /**
     * Method to Print The state for this Peer, client request
     *
     * @throws RemoteException on error with RMI
     */
    @Override
    public void state() throws RemoteException {
        log.info("Received STATE client request");
        clientRequests.submit(this::_state);
    }

    /**
     * Method to send the state to the client
     *
     * @return the Internal State
     */
    private String _state() {
        String state = this.internalState.toString();
        if (callbackInterface != null) {
            try {
                callbackInterface.notify(state);
            } catch (RemoteException e) {
                log.error("Could not notify client");
            }
        }
        return state;
    }

    /**
     * Method to find a successor for a guid, client request
     *
     * @param guid GUID to be found the successor of
     */
    @Override
    public void clientFindSuccessor(int guid) {
        clientRequests.submit(() -> _clientFindSuccessor(guid));
    }

    /**
     * Method to send the notification (reply) to the client, when the successor for
     * the guid is found
     *
     * @param guid target guid
     */
    private void _clientFindSuccessor(int guid) {
        sendNotification(super.findSuccessor(guid).toString());
    }

    /**
     * Method to reclaim the storage, client request
     *
     * @param size Size to be reclaimed, if 0 it will delete every file and reset the capacity to the default
     * @throws RemoteException on error with RMI
     */
    @Override
    public void reclaim(long size) throws RemoteException {
        clientRequests.submit(() -> _reclaim(size));
    }

    /**
     * Method to reclaim the storage, this method deletes the necessary files, without a specific order,
     * and sends a REMOVED message to the file's owner, signaling this peer is no longer serving a file,
     * and the owner should start another backup operation for the file
     *
     * @param size target size, 0 if the client wishes to remove all files and reset the capacity
     */
    private void _reclaim(long size) {
        for (Map.Entry<String, PeerFile> file : this.internalState.getSavedFilesMap().entrySet()) {
            String fileId = file.getValue().getId();
            ChordReference owner = file.getValue().getOwner();

            try {
                if (Files.deleteIfExists(Path.of(getFileLocation(fileId)))) {
                    String body = String.join(":", Arrays.asList(fileId, String.valueOf(file.getKey())));

                    SSLConnection connection = this.connectToPeer(owner.getAddress());
                    assert connection != null;

                    log.info("Removed file: {}", file);
                    this.getSavedFilesMap().remove(fileId);
                    this.send(connection, new Removed(getReference(), body.getBytes(StandardCharsets.UTF_8)));
                }
            } catch (IOException e) {
                log.error("Error deleting file: {}: {}", file, e.getMessage());
            }

            try {
                this.internalState.updateOccupation();
            } catch (IOException e) {
                log.error("Error updating occupation!");
            }

            // revoke all space allocated to storing files
            if (size == 0) continue;
            // reached desired space for storing files
            if (this.internalState.getOccupation() <= size) break;

            sendNotification("Reclaim Successful");
        }

        this.internalState.setCapacity(size == 0 ? Constants.DEFAULT_CAPACITY : size);
        this.sendNotification("Reclaim Successful!");
    }

    public String getFileLocation(String fileId) {
        return String.format(PeerInternalState.FILES_PATH, this.guid, fileId);
    }

    public boolean hasSpace(double size) {
        return this.internalState.hasSpace(size);
    }

    public void addSavedFile(int key, String id, ChordReference owner, long size, int replicationDegree) {
        PeerFile file = new PeerFile(key, id, owner, size, replicationDegree);
        this.internalState.addSavedFile(file);
    }

    public List<PeerFile> getSavedFiles() {
        return new ArrayList<>(this.internalState.getSavedFilesMap().values());
    }

    public ConcurrentHashMap<String, PeerFile> getSentFilesMap() {
        return this.internalState.getSentFilesMap();
    }

    public ConcurrentHashMap<String, PeerFile> getSavedFilesMap() {
        return this.internalState.getSavedFilesMap();
    }

    public PeerFile getSavedFile(String fileId) {
        return this.internalState.getSavedFilesMap().get(fileId);
    }

    public void start() {
        this.join();
    }

    @Override
    public void chord() {
        sendNotification("GUID: " + this.guid + "\n" +
                "Server Address: " + this.address + "\n" +
                "Predecessor: " + this.predecessor + "\n" +
                "Finger Table:" + "\n" +
                this.getRoutingTableString());
    }

    public String getServiceAccessPoint() {
        return sap;
    }

    public ChordReference getReference() {
        return new ChordReference(this.address, this.guid);
    }
}
