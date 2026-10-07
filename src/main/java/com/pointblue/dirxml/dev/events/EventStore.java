package com.pointblue.dirxml.dev.events;

import com.pointblue.dirxml.dev.deploy.Environments;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * A read-only connection to one environment's event store. Only the engine writes the store
 * (through the EventLogger driver); the core reads it, with the reader account, on a connection
 * marked read-only as well. Reads schema version 2; version 1 rows come back with their
 * differences handled in {@link Event}; a store above version 2 is refused.
 */
public final class EventStore implements AutoCloseable {
    public static final int READS_SCHEMA = 2;
    /** Above this many rows, free text without an indexed selector is refused. */
    public static final long TEXT_SCAN_LIMIT = 100_000;

    private final Connection conn;
    private final String table;
    private final Environments.EventsConfig config;

    private EventStore(Connection conn, Environments.EventsConfig config) {
        this.conn = conn;
        this.config = config;
        this.table = config.table;
    }

    /** Opens the environment's store; refuses an environment without one. */
    public static EventStore open(Environments.Environment env) throws IOException {
        if (env.events == null) {
            throw new IOException("environment '" + env.name + "' has no event store: set " + env.name + ".eventsUrl (and eventsUser, eventsPassword…)");
        }
        return open(env.events);
    }

    public static EventStore open(Environments.EventsConfig c) throws IOException {
        Properties p = new Properties();
        p.setProperty("user", c.user);
        p.setProperty("password", c.password);
        p.setProperty("readOnly", "true");
        p.setProperty("ApplicationName", "DirXMLDev");
        p.setProperty("loginTimeout", "15");
        try {
            Connection conn = DriverManager.getConnection(c.url, p);
            conn.setReadOnly(true);
            conn.setAutoCommit(true);
            return new EventStore(conn, c);
        } catch (SQLException e) {
            throw new IOException("event store " + c.url + ": " + e.getMessage(), e);
        }
    }

    public Environments.EventsConfig config() {
        return config;
    }

    /** What the store holds: rows, newest time, schema versions present, whether it is migrated. */
    public Map<String, Object> describe() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", config.url);
        m.put("table", table);
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*), max(cachedtime), min(schemaversion), max(schemaversion) FROM " + table)) {
                rs.next();
                m.put("rows", rs.getLong(1));
                java.sql.Timestamp newest = rs.getTimestamp(2);
                m.put("newest", newest == null ? null : newest.toInstant().toString());
                m.put("minSchemaVersion", rs.getObject(3) == null ? null : rs.getInt(3));
                m.put("maxSchemaVersion", rs.getObject(4) == null ? null : rs.getInt(4));
            }
            m.put("migrated", true);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("schemaversion")) {
                // a table from before release 2.0.0: no schemaversion, no policy columns
                m.put("migrated", false);
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*), max(cachedtime) FROM " + table)) {
                    rs.next();
                    m.put("rows", rs.getLong(1));
                    java.sql.Timestamp newest = rs.getTimestamp(2);
                    m.put("newest", newest == null ? null : newest.toInstant().toString());
                } catch (SQLException e2) {
                    throw new IOException("event store: " + e2.getMessage(), e2);
                }
                return m;
            }
            throw new IOException("event store: " + e.getMessage(), e);
        }
        Object max = m.get("maxSchemaVersion");
        if (max instanceof Integer && (Integer) max > READS_SCHEMA) {
            throw new IOException("the store holds schema version " + max + " rows; this DirXMLDev reads version " + READS_SCHEMA);
        }
        return m;
    }

    /** The rows a query selects. Free text alone on a large store is refused. */
    public List<Event> query(EventQuery q) throws IOException {
        if (q.text != null && !q.indexed()) {
            long rows = count();
            if (rows > TEXT_SCAN_LIMIT) {
                throw new IOException("free text over " + rows + " rows scans the whole table: add --since, --dn, --under, --name, --driver or --event-id");
            }
        }
        EventQuery.Sql sql = q.sql(table);
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.text())) {
            for (int i = 0; i < sql.params().size(); i++) {
                ps.setObject(i + 1, sql.params().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Event.fromRow(row(rs)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("event store: " + e.getMessage(), e);
        }
        return out;
    }

    /** One row by its id, or null. */
    public Event get(long id) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, eventid, classname, srcdn, srcentryid, eventtype, cachedtime, srcdriver, channel, policy, stage, schemaversion, eventjson::text AS eventjson, xmlevent FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Event.fromRow(row(rs)) : null;
            }
        } catch (SQLException e) {
            throw new IOException("event store: " + e.getMessage(), e);
        }
    }

    /** Every row of one engine event: the driver's row first, then each policy stage. */
    public List<Event> siblings(String eventId) throws IOException {
        EventQuery q = new EventQuery();
        q.eventId = eventId;
        q.limit = EventQuery.MAX_LIMIT;
        return query(q);
    }

    /** The distinct driver DNs rows came from, slash form. */
    public List<String> drivers() throws IOException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT DISTINCT srcdriver FROM " + table + " WHERE srcdriver IS NOT NULL ORDER BY srcdriver")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IOException("event store: " + e.getMessage(), e);
        }
        return out;
    }

    /** The tree name the store's DNs start with (from any driver DN), or null on an empty store. */
    public String treeName() throws IOException {
        for (String dn : drivers()) {
            if (dn.startsWith("\\")) {
                int i = dn.indexOf('\\', 1);
                return i < 0 ? dn.substring(1) : dn.substring(1, i);
            }
        }
        return null;
    }

    public long count() throws IOException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IOException("event store: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            m.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
        }
        return m;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            // closing
        }
    }
}
