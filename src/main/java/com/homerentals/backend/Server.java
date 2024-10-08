package com.homerentals.backend;

import com.homerentals.dao.GuestAccountDAO;
import com.homerentals.domain.*;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;

public class Server {
    // TODO: Replace System.out.println() with logger in log file.
    protected final static ArrayList<WorkerInfo> workers = new ArrayList<>();
    protected final static HashMap<Integer, MapResult> mapReduceResults = new HashMap<>();
    private final static GuestAccountDAO guestAccountDAO = new GuestAccountDAO();

    private static int numberOfRentals;
    private static int mapId;
    private static int bookingId;

    // Used for synchronizing server getNextId requests
    protected final static Object mapIdSyncObj = new Object();
    protected final static Object rentalIdSyncObj = new Object();
    protected final static Object bookingIdSyncObj = new Object();

    public static int getNextRentalId() {
        return numberOfRentals++;
    }

    public static int getNextMapId() {
        return mapId++;
    }

    public static String getNextBookingId() {
        return String.valueOf(bookingId++);
    }

    protected static int hash(int rentalId) {
        int numOfWorkers = workers.size();
        float A = 0.357840f;
        return (int) Math.floor(numOfWorkers * ((rentalId * A) % 1));
    }

    protected static boolean userExists(String email, String password) {
        return guestAccountDAO.find(email, password) != null;
    }

    protected static GuestAccount getUser(String email) {
        return guestAccountDAO.find(email);
    }

    protected static void addUser(Email email, Password password, String firstName, String lastName, PhoneNumber phoneNumber) {
        GuestAccount guestAccount = new GuestAccount(email, password, firstName, lastName, phoneNumber);
        guestAccountDAO.save(guestAccount);
    }

    protected static ArrayList<BookingReference> getGuestBookings(String email) {
        GuestAccount guestAccount = guestAccountDAO.find(email);
        if (guestAccount == null) {
            return null;
        }
        return guestAccount.getUnratedBookings();
    }

    protected static void addBookingToGuest(String email, String bookingId, int rentalId, String rentalName, String rentalLocation, LocalDate startDate, LocalDate endDate) {
        GuestAccount guestAccount = guestAccountDAO.find(email);
        if (guestAccount == null) {
            return;
        }
        guestAccount.addBooking(bookingId, rentalId, rentalName, rentalLocation, startDate, endDate);
    }

    protected static void rateGuestsBooking(String email, String bookingId) {
        GuestAccount guestAccount = guestAccountDAO.find(email);
        if (guestAccount == null) {
            return;
        }
        guestAccount.rateBooking(bookingId);
    }

    protected static String sendMessageToWorkerAndWaitForResponse(String msg, int workerId) {
        WorkerInfo workerInfo = workers.get(workerId);
        String workerAddress = workerInfo.getAddress();
        int workerPort = Integer.parseInt(workerInfo.getPort());

        try (Socket workerSocket = new Socket(workerAddress, workerPort);
             DataOutputStream workerSocketOutput = new DataOutputStream(workerSocket.getOutputStream());
             DataInputStream workerSocketInput = new DataInputStream(workerSocket.getInputStream())
        ) {
            workerSocketOutput.writeUTF(msg);
            workerSocketOutput.flush();

            // Receive response
            return workerSocketInput.readUTF();
        } catch (IOException e) {
            System.err.printf("\n! Server.writeToWorkerSocketAndWaitForResponse(): Failed to set up Socket to Worker: %s%n%s%n", workerInfo, e);
            return null;
        }
    }

    private static void writeToWorkerSocket(String msg, int workerId) throws IOException {
        WorkerInfo workerInfo = workers.get(workerId);
        String workerAddress = workerInfo.getAddress();
        int workerPort = Integer.parseInt(workerInfo.getPort());

        try (Socket workerSocket = new Socket(workerAddress, workerPort);
             DataOutputStream workerSocketOutput = new DataOutputStream(workerSocket.getOutputStream())
        ) {
            workerSocketOutput.writeUTF(msg);
            workerSocketOutput.flush();
        } catch (IOException e) {
            System.err.println("\n! Server.writeToWorkerSocket(): Failed to write to Worker: " + workerInfo);
            throw e;
        }
    }

    protected static void sendMessageToWorker(String msg, int workerId) {
        try {
            writeToWorkerSocket(msg, workerId);
        } catch (IOException e) {
            System.err.println("\n! Server.sendMessageToWorker(): Failed to write to Worker: " + workerId);
        }
    }

    protected static void broadcastMessageToWorkers(String msg) {
        for (int w = 0; w < workers.size(); w++) {
            sendMessageToWorker(msg, w);
        }
    }

    private static void setUpRental(String path, int rentalId, String bookingStartDate, String bookingEndDate) throws InterruptedException {
        JSONObject rentalJson = BackendUtils.readFile(path, true);
        if (rentalJson != null) {
            BackendUtils.executeNewRentalRequest(rentalJson, Requests.NEW_RENTAL.name());
        } else {
            return;
        }
        Thread.sleep(1000);

        // Make available for 2023
        JSONObject body = new JSONObject();
        body.put(BackendUtils.BODY_FIELD_RENTAL_ID, rentalId);
        body.put(BackendUtils.BODY_FIELD_START_DATE, "01/01/2023");
        body.put(BackendUtils.BODY_FIELD_END_DATE, "31/12/2023");
        JSONObject request = BackendUtils.createRequest(Requests.UPDATE_AVAILABILITY.name(), body.toString());
        BackendUtils.executeUpdateAvailability(request.toString(), body);
        Thread.sleep(1000);
        // Make available for 2024
        body = new JSONObject();
        body.put(BackendUtils.BODY_FIELD_RENTAL_ID, rentalId);
        body.put(BackendUtils.BODY_FIELD_START_DATE, "01/01/2024");
        body.put(BackendUtils.BODY_FIELD_END_DATE, "31/12/2024");
        request = BackendUtils.createRequest(Requests.UPDATE_AVAILABILITY.name(), body.toString());
        BackendUtils.executeUpdateAvailability(request.toString(), body);
        Thread.sleep(1000);

        // Add new Booking
        if (bookingStartDate != null && bookingEndDate != null) {
            body = new JSONObject();
            body.put(BackendUtils.BODY_FIELD_RENTAL_ID, rentalId);
            body.put(BackendUtils.BODY_FIELD_START_DATE, bookingStartDate);
            body.put(BackendUtils.BODY_FIELD_END_DATE, bookingEndDate);
            body.put(BackendUtils.BODY_FIELD_GUEST_EMAIL, "guest@example.com");
            BackendUtils.executeNewBookingRequest(body, Requests.NEW_BOOKING.name());
            Thread.sleep(1000);
        }
    }

    private static void setUp() throws IOException, InterruptedException {
        // Add guest account
        Email email = new Email("guest@example.com");
        Password password = new Password("guest");
        PhoneNumber phoneNumber = new PhoneNumber("123456789");
        GuestAccount guestAccount = new GuestAccount(email, password, "Guest", "Guest", phoneNumber);
        guestAccountDAO.save(guestAccount);

        // Add cozy_rental_crete
        setUpRental(BackendUtils.inputsPath + "cozy_rental_crete/cozy_rental_crete", 0, "01/01/2023", "31/12/2023");

        // Add lux_rental_crete
        setUpRental(BackendUtils.inputsPath + "lux_rental_crete/lux_rental_crete", 1, "01/10/2023", "02/10/2023");

        // Add best_spitarwn_zante
        setUpRental(BackendUtils.inputsPath + "best_spitarwn_zante/best_spitarwn_zante", 2, "01/10/2023", "28/12/2023");

        // Add city_center_apartment_athens
        setUpRental(BackendUtils.inputsPath + "city_center_apartment_athens/city_center_apartment_athens", 3, null, null);

        // Add lux_apartment_skg
        setUpRental(BackendUtils.inputsPath + "lux_apartment_skg/lux_apartment_skg", 4, null, null);

        // Add lux_penthouse_athens
        setUpRental(BackendUtils.inputsPath + "lux_penthouse_athens/lux_penthouse_athens", 5, null, null);

        // Add lux_resort_paros
        setUpRental(BackendUtils.inputsPath + "lux_resort_paros/lux_resort_paros", 6, null, null);

        // Add summer_house_paros
        setUpRental(BackendUtils.inputsPath + "summer_house_paros/summer_house_paros", 7, null, null);

        // Add summer_house_santorini
        setUpRental(BackendUtils.inputsPath + "summer_house_santorini/summer_house_santorini", 8, null, null);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Server <amount_of_workers>");
            System.exit(1);
        }
        int amountOfWorkers = 0;
        try {
            amountOfWorkers = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("\n! Server.main(): Invalid argument given for amount of workers.\n" + e);
            System.exit(0);
        }
        workers.clear();

        try (ServerSocket serverSocket = new ServerSocket(BackendUtils.SERVER_PORT, 10)) {
            serverSocket.setReuseAddress(true);

            // Listen to incoming worker connections
            for (int i = 0; i < amountOfWorkers; i++) {
                Socket workerSocket = serverSocket.accept();
                SocketAddress workerAddress = workerSocket.getRemoteSocketAddress();
                System.out.printf("\n> Worker:%s connected.%n", workerAddress);
                try (DataInputStream workerSocketIn = new DataInputStream(workerSocket.getInputStream())) {
                    String workerPort = workerSocketIn.readUTF();
                    workers.add(new WorkerInfo(workerAddress, workerPort));
                } catch (IOException e) {
                    System.err.println("\n! Server.main(): Failed to read port from Worker: " + workerAddress);
                }
            }

            // Start thread that listens to Reducer
            Socket reducerSocket = serverSocket.accept();
            System.out.printf("\n> Reducer:%s connected.%n", reducerSocket.getInetAddress().toString());
            ReducerHandler reducerHandler = new ReducerHandler(reducerSocket);
            new Thread(reducerHandler).start();

            Server.setUp();

            // Handle client requests
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("\n> New client connected: " + clientSocket.getInetAddress().getHostAddress());
                ClientHandler clientThread = new ClientHandler(clientSocket);
                new Thread(clientThread).start();
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            System.err.println("\n! Server.main(): Error:\n" + e);
            e.printStackTrace();
        }
    }
}
