package at.shorty.polar.addon.config;

import at.shorty.polar.addon.data.LogCountData;
import at.shorty.polar.addon.data.LogEntry;
import at.shorty.polar.addon.util.TimeRange;
import lombok.Data;
import lombok.Setter;
import org.bukkit.configuration.ConfigurationSection;
import top.polar.api.check.Check;
import top.polar.api.user.User;
import top.polar.api.user.event.type.CloudCheckType;
import top.polar.api.user.event.type.PunishmentType;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class Logs {

    public transient boolean enabled;
    public transient int expireAfterDays;
    public transient Database database;
    public transient String context;
    public transient LogStore store;
    public transient String timestampFormat;
    public transient MitigationTuning mitigationTuning;
    public transient String mitigationMessage, mitigationHoverText, detectionMessage, detectionHoverText, cloudDetectionMessage, cloudDetectionHoverText, punishmentMessage, punishmentHoverText;
    @Setter
    private Connection connection;

    public int countLogs(String context) throws SQLException {
        if (!context.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid log context name, must be [a-zA-Z0-9_]");
        }
        PreparedStatement preparedStatement = connection.prepareStatement("SELECT COUNT(*) FROM polar_logs_" + context);
        preparedStatement.executeQuery();
        try (ResultSet resultSet = preparedStatement.getResultSet()) {
            resultSet.next();
            return resultSet.getInt(1);
        } finally {
            preparedStatement.close();
        }
    }

    public LogCountData getLogCountData(String context, String player, @Nullable TimeRange timeRange) throws SQLException {
        if (!context.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid log context name, must be [a-zA-Z0-9_]");
        }
        Map<String, Integer> mitigation = new HashMap<>();
        Map<String, Integer> detection = new HashMap<>();
        Map<String, Integer> cloudDetection = new HashMap<>();
        Map<String, Integer> punishment = new HashMap<>();
        PreparedStatement preparedStatement;

        if (timeRange == null) {
            preparedStatement = connection.prepareStatement("SELECT type, check_type, COUNT(*) as count FROM polar_logs_" + context + " WHERE player_name = ? GROUP BY check_type, type;");
        } else {
            preparedStatement = connection.prepareStatement("SELECT type, check_type, COUNT(*) as count FROM polar_logs_" + context + " WHERE player_name = ? AND ROUND(UNIX_TIMESTAMP(timestamp) * 1000) BETWEEN ? AND ? GROUP BY check_type, type;");
            preparedStatement.setLong(2, timeRange.start);
            preparedStatement.setLong(3, timeRange.end);
        }
        preparedStatement.setString(1, player);
        preparedStatement.executeQuery();
        try (ResultSet resultSet = preparedStatement.getResultSet()) {
            while (resultSet.next()) {
                String type = resultSet.getString(1);
                String checkType = resultSet.getString(2);
                if (checkType == null || type.equals("punishment")) continue;
                int count = resultSet.getInt(3);
                switch (type) {
                    case "mitigation":
                        mitigation.put(checkType, count);
                        break;
                    case "detection":
                        detection.put(checkType, count);
                        break;
                    case "cloud_detection":
                        cloudDetection.put(checkType, count);
                        break;
                }
            }
        } finally {
            preparedStatement.close();
        }
        PreparedStatement punishmentStatement;
        if (timeRange == null) {
            punishmentStatement = connection.prepareStatement("SELECT punishment_type, COUNT(*) as count FROM polar_logs_" + context + " WHERE player_name = ? AND type = 'punishment' GROUP BY punishment_type;");
        } else {
            punishmentStatement = connection.prepareStatement("SELECT punishment_type, COUNT(*) as count FROM polar_logs_" + context + " WHERE player_name = ? AND type = 'punishment' AND ROUND(UNIX_TIMESTAMP(timestamp) * 1000) BETWEEN ? AND ? GROUP BY punishment_type;");
            punishmentStatement.setLong(2, timeRange.start);
            punishmentStatement.setLong(3, timeRange.end);
        }
        punishmentStatement.setString(1, player);
        punishmentStatement.executeQuery();
        try (ResultSet resultSet = punishmentStatement.getResultSet()) {
            while (resultSet.next()) {
                String type = resultSet.getString(1);
                int count = resultSet.getInt(2);
                punishment.put(type, count);
            }
        } finally {
            punishmentStatement.close();
        }
        return new LogCountData(mitigation, detection, cloudDetection, punishment);
    }

    public List<LogEntry> getLogEntries(String context, String player, int limit, int offset, @Nullable TimeRange timeRange) throws SQLException {
        if (!context.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid log context name, must be [a-zA-Z0-9_]");
        }
        PreparedStatement preparedStatement;
        if (timeRange == null) {
            preparedStatement = connection.prepareStatement("SELECT * FROM polar_logs_" + context + " WHERE player_name = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?;");
        } else {
            preparedStatement = connection.prepareStatement("SELECT * FROM polar_logs_" + context + " WHERE player_name = ? AND ROUND(UNIX_TIMESTAMP(timestamp) * 1000) BETWEEN ? AND ? ORDER BY timestamp DESC LIMIT ? OFFSET ?;");
            preparedStatement.setLong(2, timeRange.start);
            preparedStatement.setLong(3, timeRange.end);
        }
        preparedStatement.setString(1, player);
        preparedStatement.setInt(timeRange == null ? 2 : 4, limit);
        preparedStatement.setInt(timeRange == null ? 3 : 5, offset);
        preparedStatement.executeQuery();
        try {
            return getLogEntries(preparedStatement);
        } finally {
            preparedStatement.close();
        }
    }

    private static List<LogEntry> getLogEntries(PreparedStatement preparedStatement) throws SQLException {
        ResultSet resultSet = preparedStatement.getResultSet();
        List<LogEntry> logEntries = new ArrayList<>();
        while (resultSet.next()) {
            String type = resultSet.getString("type");
            String playerName = resultSet.getString("player_name");
            String playerUuid = resultSet.getString("player_uuid");
            String playerVersion = resultSet.getString("player_version");
            int playerLatency = resultSet.getInt("player_latency");
            String playerBrand = resultSet.getString("player_brand");
            double vl = resultSet.getDouble("vl");
            String checkType = resultSet.getString("check_type");
            String checkName = resultSet.getString("check_name");
            String details = resultSet.getString("details");
            String punishmentType = resultSet.getString("punishment_type");
            String punishmentReason = resultSet.getString("punishment_reason");
            long time = resultSet.getTimestamp("timestamp").getTime();
            LogEntry logEntry = new LogEntry(type, playerName, playerUuid, playerVersion, playerLatency, playerBrand, vl, checkType, checkName, details, punishmentType, punishmentReason, time);
            logEntries.add(logEntry);
        }
        return logEntries;
    }

    public void logMitigation(User user, Check check, String details) {
        if (!store.isMitigation()) return;
        if (mitigationTuning.getLogTypes().contains(check.type().name()) && check.violationLevel() < mitigationTuning.getMinVl())
            return;
        String clientVersion = user.clientVersion().name();
        String brand = user.clientVersion().brand();
        if (clientVersion.length() > 20) {
            clientVersion = clientVersion.substring(0, 20);
        }
        if (brand.length() > 64) {
            brand = brand.substring(0, 64);
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO polar_logs_" + context + " (type, player_name, player_uuid, player_version, player_latency, player_brand, vl, check_type, check_name, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            preparedStatement.setString(1, "mitigation");
            preparedStatement.setString(2, user.username());
            preparedStatement.setString(3, user.uuid().toString());
            preparedStatement.setString(4, clientVersion);
            preparedStatement.setInt(5, (int) user.connection().latency());
            preparedStatement.setString(6, brand);
            preparedStatement.setDouble(7, check.violationLevel());
            preparedStatement.setString(8, check.type().name());
            preparedStatement.setString(9, check.name());
            preparedStatement.setString(10, details);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void logDetection(User user, Check check, String details) {
        if (!store.isDetection()) return;
        String clientVersion = user.clientVersion().name();
        String brand = user.clientVersion().brand();
        if (clientVersion.length() > 20) {
            clientVersion = clientVersion.substring(0, 20);
        }
        if (brand.length() > 64) {
            brand = brand.substring(0, 64);
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO polar_logs_" + context + " (type, player_name, player_uuid, player_version, player_latency, player_brand, vl, check_type, check_name, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            preparedStatement.setString(1, "detection");
            preparedStatement.setString(2, user.username());
            preparedStatement.setString(3, user.uuid().toString());
            preparedStatement.setString(4, clientVersion);
            preparedStatement.setInt(5, (int) user.connection().latency());
            preparedStatement.setString(6, brand);
            preparedStatement.setDouble(7, check.violationLevel());
            preparedStatement.setString(8, check.type().name());
            preparedStatement.setString(9, check.name());
            preparedStatement.setString(10, details);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void logCloudDetection(User user, CloudCheckType checkType, String details) {
        if (!store.isCloudDetection()) return;
        String clientVersion = user.clientVersion().name();
        String brand = user.clientVersion().brand();
        if (clientVersion.length() > 20) {
            clientVersion = clientVersion.substring(0, 20);
        }
        if (brand.length() > 64) {
            brand = brand.substring(0, 64);
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO polar_logs_" + context + " (type, player_name, player_uuid, player_version, player_latency, player_brand, check_type, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            preparedStatement.setString(1, "cloud_detection");
            preparedStatement.setString(2, user.username());
            preparedStatement.setString(3, user.uuid().toString());
            preparedStatement.setString(4, clientVersion);
            preparedStatement.setInt(5, (int) user.connection().latency());
            preparedStatement.setString(6, brand);
            preparedStatement.setString(7, checkType.name());
            preparedStatement.setString(8, details);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void logPunishment(User user, PunishmentType type, String reason) {
        if (!store.isPunishment()) return;
        try (PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO polar_logs_" + context + " (type, player_name, player_uuid, punishment_type, punishment_reason) VALUES (?, ?, ?, ?, ?)")) {
            preparedStatement.setString(1, "punishment");
            preparedStatement.setString(2, user.username());
            preparedStatement.setString(3, user.uuid().toString());
            preparedStatement.setString(4, type.name());
            preparedStatement.setString(5, reason);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Logs loadFromConfigSection(ConfigurationSection section) {
        Logs logs = new Logs();
        logs.setEnabled(section.getBoolean("enabled"));
        logs.setExpireAfterDays(section.getInt("expire_after_days"));
        logs.setDatabase(Database.loadFromConfigSection(section.getConfigurationSection("database")));
        logs.setContext(section.getString("context"));
        logs.setStore(LogStore.loadFromConfigSection(section.getConfigurationSection("store")));
        logs.setTimestampFormat(section.getString("timestamp_format"));
        logs.setMitigationTuning(MitigationTuning.loadFromConfigSection(section.getConfigurationSection("mitigation")));
        logs.setMitigationMessage(section.getConfigurationSection("mitigation").getString("message"));
        logs.setMitigationHoverText(section.getConfigurationSection("mitigation").getString("hover_text"));
        logs.setDetectionMessage(section.getConfigurationSection("detection").getString("message"));
        logs.setDetectionHoverText(section.getConfigurationSection("detection").getString("hover_text"));
        logs.setCloudDetectionMessage(section.getConfigurationSection("cloud_detection").getString("message"));
        logs.setCloudDetectionHoverText(section.getConfigurationSection("cloud_detection").getString("hover_text"));
        logs.setPunishmentMessage(section.getConfigurationSection("punishment").getString("message"));
        logs.setPunishmentHoverText(section.getConfigurationSection("punishment").getString("hover_text"));
        return logs;
    }

    @Data
    public static class Database {

        public transient String sqlHost, sqlPort, sqlDatabase, sqlUsername, sqlPassword;

        public static Database loadFromConfigSection(ConfigurationSection section) {
            Database database = new Database();
            database.setSqlHost(section.getString("sql_host"));
            database.setSqlPort(section.getString("sql_port"));
            database.setSqlDatabase(section.getString("sql_database"));
            database.setSqlUsername(section.getString("sql_username"));
            database.setSqlPassword(section.getString("sql_password"));
            return database;
        }

    }

    @Data
    public static class LogStore {

        public transient boolean mitigation, detection, cloudDetection, punishment;

        public static LogStore loadFromConfigSection(ConfigurationSection section) {
            LogStore logStore = new LogStore();
            logStore.setMitigation(section.getBoolean("mitigation"));
            logStore.setDetection(section.getBoolean("detection"));
            logStore.setCloudDetection(section.getBoolean("cloud_detection"));
            logStore.setPunishment(section.getBoolean("punishment"));
            return logStore;
        }

    }

    @Data
    public static class MitigationTuning {

        public transient List<String> logTypes;
        public transient int minVl;

        public static MitigationTuning loadFromConfigSection(ConfigurationSection section) {
            MitigationTuning mitigationTuning = new MitigationTuning();
            mitigationTuning.setLogTypes(section.getStringList("log_types"));
            mitigationTuning.setMinVl(section.getInt("min_vl"));
            return mitigationTuning;
        }
    }
}