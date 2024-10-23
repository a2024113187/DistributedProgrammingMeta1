package com.projectMeta1;

import java.io.*;
import java.net.*;
import java.sql.*;
import java.nio.file.*;
import java.util.Timer;
import java.util.TimerTask;

public class BackupServer {
    private static final String MULTICAST_ADDRESS = "230.44.44.44";
    private static final int MULTICAST_PORT = 4444;
    private static int tcpPort;
    private static String backupDirectory;
    private static Connection connection;
    private static int localDbVersion = 0;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java BackupServer <directorio_backup>");
            return;
        }

        backupDirectory = args[0];

        // Verificar si el directorio de respaldo está vacío
        File dir = new File(backupDirectory);
        if (dir.exists() && dir.list().length > 0) {
            System.out.println("El directorio de respaldo no está vacío. La aplicación se cerrará.");
            return;
        }

        // Iniciar la recepción de heartbeats
        recibirHeartbeats();

        // Conectar al servidor principal y obtener la base de datos
        obtenerBaseDeDatosDesdeServidorPrincipal();
    }

    private static void recibirHeartbeats() {
        new Thread(() -> {
            try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
                InetAddress grupo = InetAddress.getByName(MULTICAST_ADDRESS);
                socket.joinGroup(grupo);
                socket.setSoTimeout(30000); // 30 segundos de timeout

                while (true) {
                    try {
                        byte[] buffer = new byte[256];
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        String mensaje = new String(packet.getData(), 0, packet.getLength());
                        System.out.println("Heartbeat recibido: " + mensaje);
                        procesarHeartbeat(mensaje);
                    } catch (SocketTimeoutException e) {
                        System.out.println("No se recibió heartbeat durante 30 segundos. El servidor de backup se cerrará.");
                        return; // Terminar si no se recibe heartbeat
                    }
                }
            } catch (IOException e) {
                System.out.println("Error en la recepción de heartbeats: " + e.getMessage());
            }
        }).start();
    }

    private static void procesarHeartbeat(String mensaje) {
        // Extraer puerto y versión del mensaje
        String[] partes = mensaje.split(", ");
        if (partes.length < 2) return;

        int version = Integer.parseInt(partes[0].split(": ")[1]);
        tcpPort = Integer.parseInt(partes[1].split(": ")[1]);

        if (version != localDbVersion && version != localDbVersion + 1) {
            System.out.println("La versión de la base de datos ha cambiado inesperadamente. El servidor de backup se cerrará.");
            System.exit(0);
        }
    }

    private static void obtenerBaseDeDatosDesdeServidorPrincipal() {
        try (Socket socket = new Socket("localhost", tcpPort);
             InputStream inputStream = socket.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(backupDirectory + "/backup.db")) {

            // Copiar la base de datos
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            System.out.println("Base de datos copiada exitosamente a: " + backupDirectory + "/backup.db");
            iniciarBaseDeDatos(backupDirectory + "/backup.db");

        } catch (IOException e) {
            System.out.println("Error al obtener la base de datos del servidor principal: " + e.getMessage());
        }
    }

    private static void iniciarBaseDeDatos(String rutaDb) {
        try {
            String url = "jdbc:sqlite:" + rutaDb;
            connection = DriverManager.getConnection(url);
            localDbVersion = obtenerVersionBaseDatos();
            System.out.println("Conexión a la base de datos de respaldo establecida. Versión: " + localDbVersion);
        } catch (SQLException e) {
            System.out.println("Error al conectar con la base de datos: " + e.getMessage());
        }
    }

    private static int obtenerVersionBaseDatos() {
        String sql = "SELECT numero FROM version LIMIT 1";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt("numero");
            }
        } catch (SQLException e) {
            System.out.println("Error al obtener la versión de la base de datos: " + e.getMessage());
        }
        return 0; // Retornar 0 si hay un error
    }
}
