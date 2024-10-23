package com.projectMeta1;

import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainServer {
    private static Connection connection;
    private static int dbVersion = 0;  // Versión de la base de datos
    private static final String MULTICAST_ADDRESS = "230.44.44.44";
    private static final int MULTICAST_PORT = 4444;
    private static int tcpPort;
    public static List<PrintWriter> clientWriters = new ArrayList<>();

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java MainServer <puerto> <ruta_db>");
            return;
        }

        tcpPort = Integer.parseInt(args[0]);
        String rutaDb = args[1];

        // Conectar a la base de datos
        iniciarBaseDeDatos(rutaDb);

        // Iniciar servidor TCP para aceptar clientes
        iniciarServidorTCP();
    }

    private static void iniciarServidorTCP() {
        try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
            System.out.println("Servidor principal iniciado en el puerto: " + tcpPort);


            // Iniciar el envío de heartbeats cada 10 segundos
            iniciarHeartbeats();

            // Escuchar conexiones entrantes de clientes
            while (true) {
                Socket clienteSocket = serverSocket.accept();
                System.out.println("Cliente conectado: " + clienteSocket.getInetAddress());
                new Thread(new ManejarCliente(clienteSocket)).start();  // Crear un nuevo hilo por cliente
            }
        } catch (IOException e) {
            System.out.println("Error en el servidor TCP: " + e.getMessage());
        }
    }

    private static void iniciarBaseDeDatos(String rutaDb) {
        try {
            String url = "jdbc:sqlite:" + rutaDb;
            File dbFile = new File(rutaDb);
            if (!dbFile.exists()) {
                // Crear la base de datos si no existe
                connection = DriverManager.getConnection(url);
                crearTablas();
                System.out.println("Base de datos creada con versión 0.");
            } else {
                connection = DriverManager.getConnection(url);
                dbVersion = obtenerVersionBaseDatos();
                System.out.println("Conexión a la base de datos SQLite establecida. Versión: " + dbVersion);
            }
        } catch (SQLException e) {
            System.out.println("Error al conectar con la base de datos: " + e.getMessage());
        }
    }

    private static void crearTablas() {
        String crearUtilizador = "CREATE TABLE IF NOT EXISTS Utilizador (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "nome TEXT NOT NULL, " +
                "telefone TEXT, " +
                "email TEXT UNIQUE NOT NULL, " +
                "password TEXT NOT NULL);";

        String crearGrupo = "CREATE TABLE IF NOT EXISTS Grupo (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "nome TEXT UNIQUE NOT NULL);";

        String crearDespesa = "CREATE TABLE IF NOT EXISTS Despesa (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "data DATE NOT NULL, " +
                "descricao TEXT, " +
                "valor REAL NOT NULL, " +
                "pago_por INTEGER NOT NULL, " +
                "grupo_id INTEGER NOT NULL, " +
                "FOREIGN KEY (pago_por) REFERENCES Utilizador(id), " +
                "FOREIGN KEY (grupo_id) REFERENCES Grupo(id));";

        String crearPagamento = "CREATE TABLE IF NOT EXISTS Pagamento (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "data DATE NOT NULL, " +
                "valor REAL NOT NULL, " +
                "pagador_id INTEGER NOT NULL, " +
                "receptor_id INTEGER NOT NULL, " +
                "grupo_id INTEGER NOT NULL, " +
                "FOREIGN KEY (pagador_id) REFERENCES Utilizador(id), " +
                "FOREIGN KEY (receptor_id) REFERENCES Utilizador(id), " +
                "FOREIGN KEY (grupo_id) REFERENCES Grupo(id));";

        String crearGrupoUtilizador = "CREATE TABLE IF NOT EXISTS Grupo_Utilizador (" +
                "grupo_id INTEGER NOT NULL, " +
                "utilizador_id INTEGER NOT NULL, " +
                "PRIMARY KEY (grupo_id, utilizador_id), " +
                "FOREIGN KEY (grupo_id) REFERENCES Grupo(id), " +
                "FOREIGN KEY (utilizador_id) REFERENCES Utilizador(id));";

        String crearDespesaUtilizador = "CREATE TABLE IF NOT EXISTS Despesa_Utilizador (" +
                "despesa_id INTEGER NOT NULL, " +
                "utilizador_id INTEGER NOT NULL, " +
                "PRIMARY KEY (despesa_id, utilizador_id), " +
                "FOREIGN KEY (despesa_id) REFERENCES Despesa(id), " +
                "FOREIGN KEY (utilizador_id) REFERENCES Utilizador(id));";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(crearUtilizador);
            stmt.execute(crearGrupo);
            stmt.execute(crearDespesa);
            stmt.execute(crearPagamento);
            stmt.execute(crearGrupoUtilizador);
            stmt.execute(crearDespesaUtilizador);
        } catch (SQLException e) {
            System.out.println("Error al crear las tablas: " + e.getMessage());
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

    private static void iniciarHeartbeats() {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                enviarHeartbeat();
            }
        }, 0, 10000);  // Ejecutar cada 10 segundos
    }

    private static void enviarHeartbeat() {
        try {
            InetAddress grupo = InetAddress.getByName(MULTICAST_ADDRESS);
            DatagramSocket socket = new DatagramSocket();
            String mensaje = "Heartbeat - versión BD: " + dbVersion + ", puerto TCP: " + tcpPort;
            byte[] buffer = mensaje.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, grupo, MULTICAST_PORT);
            socket.send(packet);
            System.out.println("Heartbeat enviado: " + mensaje);
            socket.close();
        } catch (IOException e) {
            System.out.println("Error al enviar heartbeat: " + e.getMessage());
        }
    }

    public static void actualizarBaseDeDatos(String sql) {
        try {
            Statement stmt = connection.createStatement();
            stmt.executeUpdate(sql);
            // Incrementar la versión de la base de datos
            dbVersion++;
            stmt.executeUpdate("UPDATE version SET numero = " + dbVersion);
            enviarHeartbeat(); // Enviar heartbeat después de la actualización
            notificarClientes("Actualización realizada: " + sql);
        } catch (SQLException e) {
            System.out.println("Error al actualizar la base de datos: " + e.getMessage());
        }
    }

    private static void notificarClientes(String mensaje) {
        synchronized (clientWriters) {
            for (PrintWriter writer : clientWriters) {
                writer.println("Notificación: " + mensaje); // Enviar mensaje a cada cliente
            }
        }
    }
}

// Clase ManejarCliente
class ManejarCliente implements Runnable {
    private Socket socket;
    private PrintWriter out; // Mover el PrintWriter a un campo de instancia

    public ManejarCliente(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true); // Inicializar aquí

            synchronized (MainServer.clientWriters) {
                MainServer.clientWriters.add(out); // Agregar escritor a la lista
            }

            // Leer y responder al cliente
            String mensaje;
            while ((mensaje = in.readLine()) != null) {
                System.out.println("Mensaje del cliente: " + mensaje);
                out.println("Echo: " + mensaje);  // Responder con un "echo"
            }
        } catch (IOException e) {
            System.out.println("Error al manejar cliente: " + e.getMessage());
        } finally {
            // Remover el escritor al cerrar la conexión
            synchronized (MainServer.clientWriters) {
                if (out != null) {
                    MainServer.clientWriters.remove(out); // Eliminar escritor al cerrar
                }
            }
            try {
                socket.close();
            } catch (IOException e) {
                System.out.println("Error al cerrar socket: " + e.getMessage());
            }
        }
    }
}
