import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class P2PFileSharingApp {

    // A valid port number in the range of 1 to 65535
    private static final int PORT = 7892;
    private static final int BUFFER_SIZE = 4096;
    // The folder where shared files are located (must exist)
    private static final String SHARE_FOLDER = "shared_files";
    // The folder where downloaded files are saved
    private static final String DOWNLOAD_FOLDER = "downloads";
    
    // Global GUI components
    private static JTextArea logTextArea;
    private static JTextField ipAddressField;
    private static JTextField filenameField;
    private static JLabel serverStatusLabel;
    private static JList<String> foundHostsList;
    private static DefaultListModel<String> foundHostsModel;

    private static ExecutorService executorService = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        // Ensure the directories exist
        createFolders();

        // Create and show the GUI on the Event Dispatch Thread
        SwingUtilities.invokeLater(() -> createAndShowGUI());
    }

    private static void createFolders() {
        try {
            Files.createDirectories(Paths.get(SHARE_FOLDER));
            Files.createDirectories(Paths.get(DOWNLOAD_FOLDER));
        } catch (IOException e) {
            logMessage("Error creating directories: " + e.getMessage());
        }
    }

    private static void createAndShowGUI() {
        JFrame frame = new JFrame("P2P File Sharing");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 700);
        frame.setLayout(new BorderLayout(10, 10));

        // --- North Panel for Server Status and Control ---
        JPanel topPanel = new JPanel(new GridLayout(1, 2, 10, 10));
        serverStatusLabel = new JLabel("Server Status: Inactive", SwingConstants.CENTER);
        serverStatusLabel.setFont(new Font("Arial", Font.BOLD, 14));
        JButton startServerButton = new JButton("Start Server");
        topPanel.add(serverStatusLabel);
        topPanel.add(startServerButton);

        frame.add(topPanel, BorderLayout.NORTH);

        // --- Center Panel for Client Controls and Logs ---
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));

        // Client Controls
        JPanel clientControlPanel = new JPanel();
        clientControlPanel.setLayout(new BoxLayout(clientControlPanel, BoxLayout.Y_AXIS));
        clientControlPanel.setBorder(BorderFactory.createTitledBorder("Client Controls"));

        // Local Network Scan
        JButton scanButton = new JButton("Scan Local Network");
        foundHostsModel = new DefaultListModel<>();
        foundHostsList = new JList<>(foundHostsModel);
        JScrollPane hostsScrollPane = new JScrollPane(foundHostsList);
        hostsScrollPane.setBorder(BorderFactory.createTitledBorder("Found Hosts"));

        // Download Controls
        JPanel downloadPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        downloadPanel.setBorder(BorderFactory.createTitledBorder("Download from Host"));
        ipAddressField = new JTextField(15);
        filenameField = new JTextField(15);
        JButton downloadButton = new JButton("Download File");
        downloadPanel.add(new JLabel("IP Address:"));
        downloadPanel.add(ipAddressField);
        downloadPanel.add(new JLabel("Filename:"));
        downloadPanel.add(filenameField);
        downloadPanel.add(downloadButton);

        clientControlPanel.add(scanButton);
        clientControlPanel.add(Box.createVerticalStrut(10));
        clientControlPanel.add(hostsScrollPane);
        clientControlPanel.add(Box.createVerticalStrut(10));
        clientControlPanel.add(downloadPanel);

        centerPanel.add(clientControlPanel, BorderLayout.CENTER);

        // Log Area
        logTextArea = new JTextArea(20, 50);
        logTextArea.setEditable(false);
        JScrollPane logScrollPane = new JScrollPane(logTextArea);
        centerPanel.add(logScrollPane, BorderLayout.SOUTH);

        frame.add(centerPanel, BorderLayout.CENTER);

        // --- Event Listeners ---
        startServerButton.addActionListener(e -> executorService.submit(() -> startServer()));

        scanButton.addActionListener(e -> executorService.submit(() -> scanLocalNetwork()));

        downloadButton.addActionListener(e -> {
            String ip = ipAddressField.getText();
            String filename = filenameField.getText();
            if (!ip.isEmpty() && !filename.isEmpty()) {
                executorService.submit(() -> downloadFile(ip, filename));
            } else {
                logMessage("Error: IP address and filename must be filled out.");
            }
        });

        foundHostsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && foundHostsList.getSelectedIndex() != -1) {
                ipAddressField.setText(foundHostsList.getSelectedValue());
            }
        });

        frame.setVisible(true);
    }

    private static void logMessage(String message) {
        SwingUtilities.invokeLater(() -> logTextArea.append(message + "\n"));
    }

    // --- Server functionality ---
    private static void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            serverStatusLabel.setText("Server Status: Running on Port " + PORT);
            logMessage("\n[SERVER] Server started. Shared files:");
            Files.list(Paths.get(SHARE_FOLDER))
                 .map(Path::getFileName)
                 .map(Path::toString)
                 .forEach(filename -> logMessage(" - " + filename));
            
            while (!Thread.currentThread().isInterrupted()) {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            logMessage("[SERVER] Error starting server: " + e.getMessage());
            serverStatusLabel.setText("Server Status: Error");
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())
        ) {
            String filename = in.readUTF();
            Path filePath = Paths.get(SHARE_FOLDER, filename);
            
            if (Files.exists(filePath)) {
                out.writeUTF("OK");
                byte[] buffer = new byte[BUFFER_SIZE];
                try (InputStream fileIn = new FileInputStream(filePath.toFile())) {
                    int bytesRead;
                    while ((bytesRead = fileIn.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                logMessage("[SERVER] File '" + filename + "' successfully sent to " + clientSocket.getInetAddress().getHostAddress() + ".");
            } else {
                out.writeUTF("ERROR");
                logMessage("[SERVER] Client " + clientSocket.getInetAddress().getHostAddress() + " requested a non-existent file: '" + filename + "'.");
            }
        } catch (IOException e) {
            logMessage("[SERVER] Error during client communication with " + clientSocket.getInetAddress().getHostAddress() + ": " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                logMessage("Error closing client connection: " + e.getMessage());
            }
        }
    }

    // --- Client functionality ---
    private static void scanLocalNetwork() {
        logMessage("\n[CLIENT] Scanning local network for hosts on port " + PORT + "...");
        foundHostsModel.clear();
        String localIp = getLocalIp();
        if (localIp.equals("127.0.0.1")) {
            logMessage("[CLIENT] Could not determine local IP. Local scan is not possible.");
            return;
        }

        String baseIp = localIp.substring(0, localIp.lastIndexOf('.'));
        for (int i = 1; i < 255; i++) {
            String ip = baseIp + "." + i;
            // Start a thread to check each host
            executorService.submit(() -> checkHost(ip));
        }
    }

    private static void checkHost(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, PORT), 500); // 500ms timeout
            SwingUtilities.invokeLater(() -> foundHostsModel.addElement(ip));
            logMessage("[CLIENT] Host found: " + ip);
        } catch (IOException e) {
            // Connection failed, which is expected for most IPs
        }
    }

    private static String getLocalIp() {
        try (final DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            return socket.getLocalAddress().getHostAddress();
        } catch (SocketException | UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    private static void downloadFile(String hostIp, String filename) {
        logMessage("\n[CLIENT] Downloading '" + filename + "' from " + hostIp + "...");
        try (
            Socket socket = new Socket(hostIp, PORT);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream())
        ) {
            out.writeUTF(filename);
            String response = in.readUTF();

            if ("OK".equals(response)) {
                Path downloadPath = Paths.get(DOWNLOAD_FOLDER, filename);
                try (FileOutputStream fileOut = new FileOutputStream(downloadPath.toFile())) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        fileOut.write(buffer, 0, bytesRead);
                    }
                }
                logMessage("[CLIENT] Download of '" + filename + "' successful.");
            } else {
                logMessage("[CLIENT] Download failed: The file does not exist on the server.");
            }
        } catch (IOException e) {
            logMessage("[CLIENT] Error during download: " + e.getMessage());
        }
    }
}
