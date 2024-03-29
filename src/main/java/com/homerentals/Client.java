package com.homerentals;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Client {
    private JSONObject rentalJson = null;
    private Socket requestSocket = null;
    private DataOutputStream out = null;
    private DataInputStream in = null;

    public JSONObject getRentalJson() {
        return rentalJson;
    }

    public Socket getRequestSocket() {
        return this.requestSocket;
    }

    public void setRequestSocket(Socket requestSocket) {
        this.requestSocket = requestSocket;
        try {
            this.out = new DataOutputStream(this.requestSocket.getOutputStream());
            this.in = new DataInputStream(this.requestSocket.getInputStream());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void readFile(String path) {
        // Read JSON file
        try {
            InputStream is = Files.newInputStream(Paths.get(path));
            String jsonTxt = IOUtils.toString(is, StandardCharsets.UTF_8);

            System.out.println(jsonTxt);
            this.rentalJson = new JSONObject(jsonTxt);

        } catch (IOException | JSONException e) {
            // Could not find file or
            // File is not valid JSON Object
            throw new RuntimeException(e);
        }
    }

    private JSONObject createRequest(String header, String body) {
        JSONObject request = new JSONObject();
        request.put("type", "request");
        request.put("header", header);
        request.put("body", body);

        return request;
    }

    private void sendSocketOutput(String msg) {
        try {
            this.out.writeUTF(msg);
            this.out.flush();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void close() {
        try {
            JSONObject request = this.createRequest("close-connection", "");
            this.sendSocketOutput(request.toString());

            this.in.close();
            this.out.close();
            this.requestSocket.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        Client client = new Client();

        // Read JSON file
        try {
            client.readFile("src\\main\\java\\com\\homerentals\\util\\demo_rental.json");

            // Establish a connection
            Socket requestSocket = null;
            System.out.println("Connecting to server...");
            requestSocket = new Socket("localhost", 8080);
            client.setRequestSocket(requestSocket);

            // Write to socket
            System.out.println("Writing to server...");
            JSONObject request = client.createRequest("new-rental", client.getRentalJson().toString());
            client.sendSocketOutput(request.toString());

            // Read response from server
            // inputStream = new ObjectInputStream(socket.getInputStream());
            // String msg = (String) inputStream.readObject();

        } catch (RuntimeException | IOException e) {
            e.printStackTrace();

        } finally {
            if (client.getRequestSocket() != null) {
                try {
                    System.out.println("Closing down connection...");
                    client.close();
                } catch (RuntimeException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
