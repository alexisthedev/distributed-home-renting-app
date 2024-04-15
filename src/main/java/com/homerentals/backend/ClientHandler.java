package com.homerentals.backend;

import com.homerentals.domain.Booking;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;

class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private DataInputStream clientSocketIn = null;
    private ObjectOutputStream clientSocketOut = null;

    ClientHandler(Socket clientSocket) throws IOException {
        this.clientSocket = clientSocket;
        try {
            this.clientSocketOut = new ObjectOutputStream(this.clientSocket.getOutputStream());
            this.clientSocketIn = new DataInputStream(this.clientSocket.getInputStream());
        } catch (IOException e) {
            System.err.println("ClientHandler(): Error setting up streams: " + e);
            throw e;
        }
    }

    private String readClientSocketInput() {
        try {
            return this.clientSocketIn.readUTF();
        } catch (IOException e) {
            System.out.println("ClientHandler.readClientSocketInput(): Error reading Client Socket input: " + e);
            return null;
        }
    }

    private void sendClientSocketOutput(Object obj) throws IOException {
        try {
            clientSocketOut.writeObject(obj);
            clientSocketOut.flush();
        } catch (IOException e) {
            System.err.println("ClientHandler.sendClientSocketOutput(): Error sending Client Socket output: " + e);
            throw e;
        }
    }

    private MapResult performMapReduce(Requests header, JSONObject body) throws InterruptedException {
        int requestId = body.getInt(BackendUtils.BODY_FIELD_REQUEST_ID);
        // Create MapReduce Request
        int mapId = Server.getNextMapId();
        body.put(BackendUtils.BODY_FIELD_MAP_ID, mapId);
        JSONObject request = BackendUtils.createRequest(header.toString(), body.toString(), requestId);

        // Send request to all workers
        Server.sendMessageToWorkers(request.toString(), Server.ports);

        // Check Server.mapReduceResults for Reducer response
        MapResult mapResult = null;
        synchronized (ReducerHandler.syncObj) {
            while (!Server.mapReduceResults.containsKey(mapId)) {
                ReducerHandler.syncObj.wait();
            }
            mapResult = Server.mapReduceResults.get(mapId);
            Server.mapReduceResults.remove(mapId);
        }

        if (mapResult == null) {
            throw new InterruptedException();
        }

        return mapResult;
    }

    @Override
    public void run() {
        // Read data sent from client
        String input = null;
        boolean running = true;
        try {
            while (running) {
                input = this.readClientSocketInput();
                if (input == null) {
                    System.err.println("ClientHandler.run(): Error reading Client Socket input");
                    break;
                }
                System.out.println(input);

                // Handle JSON input
                JSONObject inputJson = new JSONObject(input);
                String inputType = inputJson.getString(BackendUtils.MESSAGE_TYPE);
                JSONObject inputBody = new JSONObject(inputJson.getString(BackendUtils.MESSAGE_BODY));
                Requests inputHeader = Requests.valueOf(inputJson.getString(BackendUtils.MESSAGE_HEADER));

                MapResult mapResult;
                String response;
                switch (inputHeader) {
                    // Guest Requests
                    case GET_RENTALS:
                        // MapReduce
                        mapResult = this.performMapReduce(inputHeader, inputBody);

                        // Send rentals to client
                        this.sendClientSocketOutput(mapResult.getRentals());
                        break;

                    case NEW_BOOKING:
                        response = BackendUtils.executeNewBookingRequest(inputBody, inputHeader.name());
                        this.sendClientSocketOutput(response);
                        break;

                    case GET_BOOKINGS_WITH_NO_RATINGS:
                        // Get result from Server.GuestAccountDAO
                        String email = inputBody.getString(BackendUtils.BODY_FIELD_GUEST_EMAIL);
                        String password = inputBody.getString(BackendUtils.BODY_FIELD_GUEST_PASSWORD);
                        ArrayList<Booking> bookings = Server.getGuestBookings(email, password);
                        this.sendClientSocketOutput(bookings);
                        break;

                    case NEW_RATING:
                        // Forward request, as it is,
                        // to worker that contains this rental
                        int workerPort = Server.ports.get(Server.hash(inputBody.getInt(BackendUtils.BODY_FIELD_RENTAL_ID)));
                        Server.sendMessageToWorker(input, workerPort);
                        break;

                    // Host Requests
                    case NEW_RENTAL:
                        BackendUtils.executeNewRentalRequest(inputBody, inputHeader.name());
                        break;

                    case UPDATE_AVAILABILITY:
                        response = BackendUtils.executeUpdateAvailability(input, inputBody);
                        this.sendClientSocketOutput(response);
                        break;

                    case GET_BOOKINGS:
                        // MapReduce
                        mapResult = this.performMapReduce(inputHeader, inputBody);

                        // Send amount of bookings per location to client
                        this.sendClientSocketOutput(mapResult.getBookingsByLocation());
                        break;

                    // Miscellaneous Requests
                    case CLOSE_CONNECTION:
                        System.out.println("ClientHandler.run(): Closing connection with client.");
                        running = false;
                        break;

                    default:
                        System.err.println("ClientHandler.run(): Request type not recognized.");
                        break;
                }
            }
        } catch (JSONException e) {
            System.err.println("ClientHandler.run(): Error: " + e);
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("ClientHandler.run(): Could not retrieve result of MapReduce: " + e);
        } catch (IOException e) {
            System.err.println("ClientHandler.run(): Could not send MapReduce results to client: " + e);
        } finally {
            try {
                System.out.println("Closing thread");
                this.clientSocketIn.close();
                this.clientSocketOut.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
