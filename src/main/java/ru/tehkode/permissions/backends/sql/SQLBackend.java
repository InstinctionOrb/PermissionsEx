/*
 * PermissionsEx - Permissions plugin for Bukkit
 * Copyright (C) 2011 t3hk0d3 http://www.tehkode.ru
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package ru.tehkode.permissions.backends.sql;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.commons.dbcp.BasicDataSource;
import org.bukkit.configuration.ConfigurationSection;
import ru.tehkode.permissions.PermissionManager;
import ru.tehkode.permissions.backends.PermissionBackend;
import ru.tehkode.permissions.backends.SchemaUpdate;
import ru.tehkode.permissions.data.Context;
import ru.tehkode.permissions.data.MatcherGroup;
import ru.tehkode.permissions.data.Qualifier;
import ru.tehkode.permissions.exceptions.PermissionBackendException;
import ru.tehkode.utils.ConcurrentProvider;
import ru.tehkode.utils.PrefixedThreadFactory;
import ru.tehkode.utils.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.io.Writer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author code
 */
public class SQLBackend extends PermissionBackend {
	private static final Pattern TABLE_PATTERN = Pattern.compile("\\{([^}]+)\\}");
	private static final SQLQueryCache DEFAULT_QUERY_CACHE;

	static {
		try {
			DEFAULT_QUERY_CACHE = new SQLQueryCache(SQLBackend.class.getResourceAsStream("/sql/default/queries.properties"), null);
		} catch (IOException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private Map<String, Object> tableNames;
	private String tablePrefix;
	private SQLQueryCache queryCache;
	private final ConcurrentMap<Integer, SQLMatcherGroup> matcherCache = new ConcurrentHashMap<>();

	private BasicDataSource ds;
	protected final String dbDriver;

	public SQLBackend(PermissionManager manager, final ConfigurationSection config) throws PermissionBackendException {
		super(manager, config, Executors.newCachedThreadPool(new PrefixedThreadFactory("PEX-sql")));
		final String dbUri = getConfig().getString("uri", "");
		final String dbUser = getConfig().getString("user", "");
		final String dbPassword = getConfig().getString("password", "");
		this.tablePrefix = getConfig().getString("prefix", "");

		if (dbUri == null || dbUri.isEmpty()) {
			getConfig().set("uri", "mysql://localhost/exampledb");
			getConfig().set("user", "databaseuser");
			getConfig().set("password", "databasepassword");
			getConfig().set("prefix", "pex");
			manager.getConfiguration().save();
			throw new PermissionBackendException("SQL connection is not configured, see config.yml");
		}
		dbDriver = dbUri.split(":", 2)[0];

		this.ds = new BasicDataSource();
		String driverClass = getDriverClass(dbDriver);
		if (driverClass != null) {
			this.ds.setDriverClassName(driverClass);
		}
		this.ds.setUrl("jdbc:" + dbUri);
		this.ds.setUsername(dbUser);
		this.ds.setPassword(dbPassword);
		this.ds.setMaxActive(20);
		this.ds.setMaxWait(200); // 4 ticks
		this.ds.setValidationQuery("SELECT 1 AS dbcp_validate");
		this.ds.setTestOnBorrow(true);

		InputStream queryLocation = getClass().getResourceAsStream("/sql/" + dbDriver + "/queries.properties");
		if (queryLocation != null) {
			try {
				this.queryCache = new SQLQueryCache(queryLocation, DEFAULT_QUERY_CACHE);
			} catch (IOException e) {
				throw new PermissionBackendException("Unable to access database-specific queries", e);
			}
		} else {
			this.queryCache = DEFAULT_QUERY_CACHE;
		}
		try (SQLConnection conn = getSQL()) {
			conn.checkConnection();
		} catch (Exception e) {
			if (e.getCause() != null && e.getCause() instanceof Exception) {
				e = (Exception) e.getCause();
			}
			throw new PermissionBackendException("Unable to connect to SQL database", e);
		}

		getManager().getLogger().info("Successfully connected to SQL database");

		addSchemaUpdate(new SchemaUpdate(3) {
			@Override
			public void performUpdate() throws PermissionBackendException {
				try (SQLConnection conn = getSQL()) {
					ResultSet entities = conn.prepAndBind("SELECT `name`, `type`, FROM `{permissions_entity}`").executeQuery();
					final List<ListenableFuture<MatcherGroup>> actionsHappening = new LinkedList<>();
					while (entities.next()) {
						SQLData entityData = new SQLData(entities.getString(1), SQLData.Type.values()[entities.getInt(2)], SQLBackend.this);
						for (Map.Entry<String, List<String>> ent : entityData.getPermissionsMap().entrySet()) {
							actionsHappening.add(createMatcherGroup(MatcherGroup.PERMISSIONS_KEY, ent.getValue(), ent.getKey() == null ? ImmutableMultimap.of(entityData.getQualifier(), entityData.getIdentifier()) :
									ImmutableMultimap.of(entityData.getQualifier(), entityData.getIdentifier(), Qualifier.WORLD, ent.getKey())));
						}

						for (Map.Entry<String, Map<String, String>> ent : entityData.getOptionsMap().entrySet()) {
							actionsHappening.add(createMatcherGroup(MatcherGroup.OPTIONS_KEY, ent.getValue(), ent.getKey() == null ? ImmutableMultimap.of(entityData.getQualifier(), entityData.getIdentifier()) :
									ImmutableMultimap.of(entityData.getQualifier(), entityData.getIdentifier(), Qualifier.WORLD, ent.getKey())));
						}

						for (Map.Entry<String, List<String>> ent : entityData.getParentsMap().entrySet()) {
							actionsHappening.add(createMatcherGroup(MatcherGroup.INHERITANCE_KEY, ent.getValue(), ent.getKey() == null ? ImmutableMultimap.of(entityData.getQualifier(), entityData.getIdentifier()) :
									ImmutableMultimap.of(entityData.getQualifier(), entityData.getIdentifier(), Qualifier.WORLD, ent.getKey())));
						}
					}

					ResultSet worlds = conn.prepAndBind("SELECT `parent`, `child` FROM `{permissions_inheritance}` WHERE `type`=2").executeQuery();
					Map<String, List<String>> worldInheritance = new HashMap<>();
					while (worlds.next()) {
						List<String> parents = worldInheritance.get(worlds.getString(2));
						if (parents == null) {
							parents = new ArrayList<>();
							worldInheritance.put(worlds.getString(2), parents);
						}
						parents.add(worlds.getString(1));
					}

					for (Map.Entry<String, List<String>> ent : worldInheritance.entrySet()) {
						actionsHappening.add(createMatcherGroup(MatcherGroup.WORLD_INHERITANCE_KEY, ent.getValue(), ImmutableMultimap.of(Qualifier.WORLD, ent.getKey())));
					}

					conn.prepAndBind("DROP TABLE `{permissions}`").execute();
					conn.prepAndBind("DROP TABLE `{permissions_entity}`").execute();
					conn.prepAndBind("DROP TABLE `{permissions_inheritance}`").execute();
					Futures.getUnchecked(Futures.allAsList(actionsHappening)); // Wait for it all to finish
				} catch (SQLException | IOException e) {
					throw new PermissionBackendException(e);
				}
			}
		});
		addSchemaUpdate(new SchemaUpdate(3) {
			@Override
			public void performUpdate() throws PermissionBackendException {
				// Change encoding for all columns to utf8mb4
				// Change collation for all columns to utf8mb4_general_ci
				try (SQLConnection conn = getSQL()) {
					conn.prep("ALTER TABLE `{permissions}` DROP INDEX `unique`, MODIFY COLUMN `permission` TEXT NOT NULL").execute();
					conn.prep("ALTER TABLE `{permissions}` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci").execute();
					conn.prep("ALTER TABLE `{permissions_entity}` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci").execute();
					conn.prep("ALTER TABLE `{permissions_inheritance}` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci").execute();
				} catch (SQLException | IOException e) {
					throw new PermissionBackendException(e);
				}
			}
		});
		addSchemaUpdate(new SchemaUpdate(1) {
			@Override
			public void performUpdate() throws PermissionBackendException {
				try (SQLConnection conn = getSQL()) {
					PreparedStatement updateStmt = conn.prep("entity.options.add");
					ResultSet res = conn.prepAndBind("SELECT `name`, `type` FROM `{permissions_entity}` WHERE `default`='1'").executeQuery();
					while (res.next()) {
						conn.bind(updateStmt, res.getString("name"), res.getInt("type"), "default", "", "true");
						updateStmt.addBatch();
					}
					updateStmt.executeBatch();

					// Update tables
					conn.prep("ALTER TABLE `{permissions_entity}` DROP COLUMN `default`").execute();
				} catch (SQLException | IOException e) {
					throw new PermissionBackendException(e);
				}
			}
		});
		addSchemaUpdate(new SchemaUpdate(0) {
			@Override
			public void performUpdate() throws PermissionBackendException {
				try (SQLConnection conn = getSQL()) {
					// TODO: Table modifications not supported in SQLite
					// Prefix/sufix -> options
					PreparedStatement updateStmt = conn.prep("entity.options.add");
					ResultSet res = conn.prepAndBind("SELECT `name`, `type`, `prefix`, `suffix` FROM `{permissions_entity}` WHERE LENGTH(`prefix`)>0 OR LENGTH(`suffix`)>0").executeQuery();
					while (res.next()) {
						String prefix = res.getString("prefix");
						if (!prefix.isEmpty() && !prefix.equals("null")) {
							conn.bind(updateStmt, res.getString("name"), res.getInt("type"), "prefix", "", prefix);
							updateStmt.addBatch();
						}
						String suffix = res.getString("suffix");
						if (!suffix.isEmpty() && !suffix.equals("null")) {
							conn.bind(updateStmt, res.getString("name"), res.getInt("type"), "suffix", "", suffix);
							updateStmt.addBatch();
						}
					}
					updateStmt.executeBatch();

					// Data type corrections

					// Update tables
					conn.prep("ALTER TABLE `{permissions_entity}` DROP KEY `name`").execute();
					conn.prep("ALTER TABLE `{permissions_entity}` DROP COLUMN `prefix`, DROP COLUMN `suffix`").execute();
					conn.prep("ALTER TABLE `{permissions_entity}` ADD CONSTRAINT UNIQUE KEY `name` (`name`, `type`)").execute();

					conn.prep("ALTER TABLE `{permissions}` DROP KEY `unique`").execute();
					conn.prep("ALTER TABLE `{permissions}` ADD CONSTRAINT UNIQUE `unique` (`name`,`permission`,`world`,`type`)").execute();
				} catch (SQLException | IOException e) {
					throw new PermissionBackendException(e);
				}
			}
		});
		this.setupAliases();
		this.deployTables();
		performSchemaUpdate();
	}

	@Override
	public int getSchemaVersion() {
		try (SQLConnection conn = getSQL()) {
			ResultSet res = conn.prepAndBind("entity.options.get", "system", 2, "schema_version", "").executeQuery();
			if (!res.next()) {
				return -1;
			}
			return res.getInt("value");
		} catch (SQLException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void setSchemaVersion(int version) {
		try (SQLConnection conn = getSQL()) {
			conn.prepAndBind("entity.options.add", "system", 2, "schema_version", "", version).execute();
		} catch (SQLException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected <T> ListenableFuture<T> execute(Callable<T> func) {
		return super.execute(func);
	}

	SQLQueryCache getQueryCache() {
		return queryCache;
	}

	protected static String getDriverClass(String alias) {
		if (alias.equals("mysql")) {
			return "com.mysql.jdbc.Driver";
		} else if (alias.equals("sqlite")) {
			return "org.sqlite.JDBC";
		} else if (alias.matches("postgres?")) {
			return "org.postgresql.Driver";
		}
		return null;
	}

	public SQLConnection getSQL() throws SQLException {
		if (ds == null) {
			throw new SQLException("SQL connection information was not correct, could not retrieve connection");
		}
		return new SQLConnection(ds.getConnection(), this);
	}

	/**
	 * Perform table name expansion on a query
	 * Example: <pre>SELECT * FROM `{permissions}`;</pre>
	 *
	 * @param query the query to get
	 * @return The expanded query
	 */
	public String expandQuery(String query) {
		String newQuery = getQueryCache().getQuery(query);
		if (newQuery != null) {
			query = newQuery;
		}
		StringBuffer ret = new StringBuffer();
		Matcher m = TABLE_PATTERN.matcher(query);
		while (m.find()) {
			m.appendReplacement(ret, getTableName(m.group(1)));
		}
		m.appendTail(ret);
		return ret.toString();
	}

	public String getTableName(String identifier) {
		if (identifier.startsWith("permissions")) { // Legacy tables
			Map<String, Object> tableNames = this.tableNames;
			if (tableNames == null) {
				return identifier;
			}

			Object ret = tableNames.get(identifier);
			if (ret == null) {
				return identifier;
			}
			return ret.toString();
		} else {
			return this.tablePrefix == null || this.tablePrefix.isEmpty() ? identifier : tablePrefix + "_" + identifier;
		}
	}

	@Override
	public Collection<String> getUserNames() {
		Set<String> ret = new HashSet<>();
		try (SQLConnection conn = getSQL()) {
			ResultSet set = conn.prepAndBind("SELECT `value` FROM `{permissions}` WHERE `type` = ? AND `name` = 'name' AND `value` IS NOT NULL", 1).executeQuery();
			while (set.next()) {
				ret.add(set.getString("value"));
			}
		} catch (SQLException | IOException e) {
			throw new RuntimeException(e);
		}
		return Collections.unmodifiableSet(ret);
	}

	SQLMatcherGroup getMatcherGroup(String name, int entityId) throws IOException, SQLException {
		while (true) {
			if (matcherCache.containsKey(entityId)) {
				SQLMatcherGroup ret = matcherCache.get(entityId);
				if (ret != null) {
					return ret;
				}
			}
			SQLMatcherGroup newGroup = new SQLMatcherGroup(this, name, entityId);
			SQLMatcherGroup oldGroup = matcherCache.put(entityId, newGroup);
			if (oldGroup != null) {
				oldGroup.invalidate();
			}
		}
	}

	void resetMatcherGroup(int entityId) {
		while (true) {
			SQLMatcherGroup ret = matcherCache.get(entityId);
			ret.invalidate();
			if (matcherCache.remove(entityId, ret)) {
				return;
			}
		}
	}

	// TODO evaluate whether some of this query work should be done async (anyway, the majority of the data *should* be received asynchronously)
	@Override
	public Iterable<MatcherGroup> getAll() {
		final ResultSet res;
		final ConcurrentProvider<MatcherGroup> provider;
		try (SQLConnection conn = getSQL()) {
			res = conn.prep("groups.get.all").executeQuery();
			if (!res.next()) {
				return Collections.emptySet();
			}
			provider = ConcurrentProvider.newProvider();
		} catch (SQLException | IOException e) {
			handleException(e, "getting all groups");
			return Collections.emptySet();
		}

		execute(new Callable<Void>() {
			@Override
			public Void call() throws SQLException, IOException {
				while (true) {
					MatcherGroup match = getMatcherGroup(res.getString("name"), res.getInt("id"));
					if (res.next()) {
						provider.provide(match);
					} else {
						provider.provideAndClose(match);
						break;
					}
				}
				return null;
			}
		});
		return provider;
	}

	@Override
	public ListenableFuture<List<MatcherGroup>> getMatchingGroups(final String type) {
		return execute(new Callable<List<MatcherGroup>>() {
			@Override
			public List<MatcherGroup> call() throws Exception {
				try (SQLConnection conn = getSQL()) {
					List<MatcherGroup> ret = new LinkedList<>();
					ResultSet res = conn.prepAndBind("groups.get.name", type).executeQuery();
					while (res.next()) {
						ret.add(getMatcherGroup(type, res.getInt("id")));
					}
					return ret;
				}
			}
		});
	}

	@Override
	public ListenableFuture<List<MatcherGroup>> getMatchingGroups(final String type, final Context context) {
		return execute(new Callable<List<MatcherGroup>>() {
			@Override
			public List<MatcherGroup> call() throws Exception {
				try (SQLConnection conn = getSQL()) {
					List<MatcherGroup> ret = new LinkedList<>();
					throw new UnsupportedOperationException("Not yet implemented");
					// TODO: Update for Context
					/*ResultSet res = conn.prepAndBind("groups.get.name_qual", type, qual.getName(), qualValue).executeQuery();
					while (res.next()) {
						ret.add(getMatcherGroup(type, res.getInt(1)));
					}
					return r*/
				}
			}
		});
	}

	private int newEntity(SQLConnection conn, String type) throws SQLException {
		PreparedStatement stmt = conn.prepAndBind("groups.create", type);
		stmt.execute();
		ResultSet res = stmt.getGeneratedKeys();
		if (res.next()) {
			return res.getInt(1);
		} else {
			throw new SQLException("No generated ID returned when creating group!");
		}
	}

	@Override
	protected ListenableFuture<MatcherGroup> createMatcherGroupImpl(final String type, final Map<String, String> entries, final Multimap<Qualifier, String> qualifiers) {
		return execute(new Callable<MatcherGroup>() {
			@Override
			public MatcherGroup call() throws Exception {
				try (SQLConnection conn = getSQL()) {
					int entityId = newEntity(conn, type);
					PreparedStatement entriesAdd = conn.prepAndBind("entries.add", entityId, "", "");
					for (Map.Entry<String, String> entry : entries.entrySet()) {
						entriesAdd.setString(2, entry.getKey());
						entriesAdd.setString(3, entry.getValue());
						entriesAdd.addBatch();
					}
					entriesAdd.executeBatch();

					PreparedStatement qualifiersAdd = conn.prepAndBind("qualifiers.add", entityId, "", "");
					for (Map.Entry<Qualifier, String> entry : qualifiers.entries()) {
						qualifiersAdd.setString(2, entry.getKey().getName());
						qualifiersAdd.setString(3, entry.getValue());
						qualifiersAdd.addBatch();
					}
					qualifiersAdd.executeBatch();

					resetMatcherGroup(entityId); // Just in case
					return getMatcherGroup(type, entityId);
				}
			}
		});
	}

	@Override
	protected ListenableFuture<MatcherGroup> createMatcherGroupImpl(final String type, final List<String> entries, final Multimap<Qualifier, String> qualifiers) {
		return execute(new Callable<MatcherGroup>() {
			@Override
			public MatcherGroup call() throws Exception {
				try (SQLConnection conn = getSQL()) {
					int entityId = newEntity(conn, type);
					PreparedStatement entriesAdd = conn.prepAndBind("entries.add", entityId, "", null);
					for (String entry : entries) {
						entriesAdd.setString(2, entry);
						entriesAdd.addBatch();
					}
					entriesAdd.executeBatch();

					PreparedStatement ret = conn.prepAndBind("qualifiers.add", entityId, "", "");
					for (Map.Entry<Qualifier, String> entry : qualifiers.entries()) {
						ret.setString(2, entry.getKey().getName());
						ret.setString(3, entry.getValue());
						ret.addBatch();
					}
					ret.executeBatch();

					resetMatcherGroup(entityId);
					return getMatcherGroup(type, entityId);
				}
			}
		});
	}

	@Override
	public ListenableFuture<Collection<String>> getAllValues(final Qualifier qualifier) {
		return execute(new Callable<Collection<String>>() {
			@Override
			public Collection<String> call() throws Exception {
				try (SQLConnection conn = getSQL()) {
					Set<String> ret = new HashSet<>();
					ResultSet res = conn.prepAndBind("qualifiers.all_values", qualifier.getName()).executeQuery();
					while (res.next()) {
						ret.add(res.getString(1));
					}
					return ret;
				}
			}
		});
	}

	@Override
	public ListenableFuture<Boolean> hasAnyQualifier(final Qualifier qualifier, final String value) {
		return execute(new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				try (SQLConnection conn = getSQL()) {
					ResultSet res = conn.prepAndBind("qualifiers.any_with_value", qualifier.getName(), value).executeQuery();
					return res.next();
				}
			}
		});
	}

	@Override
	public ListenableFuture<Void> replaceQualifier(final Qualifier qualifier, final String old, final String newVal) {
		return execute(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				try (SQLConnection conn = getSQL()) {
					ResultSet res = conn.prepAndBind("qualifiers.replace", newVal, qualifier.getName(), old).executeQuery(); // TODO: Make this a stored procedure that'll do the change and return the changed ids
					if (res.next()) {
						String[] ids = res.getString(1).split(",");
						for (String id : ids) {
							resetMatcherGroup(Integer.parseInt(id));
						}
					}
				}
				return null;
			}
		});

	}

	@Override
	public ListenableFuture<List<MatcherGroup>> allWithQualifier(final Qualifier qualifier) {
		return execute(new Callable<List<MatcherGroup>>() {
			@Override
			public List<MatcherGroup> call() throws Exception {
				try (SQLConnection conn = getSQL()) {
					List<MatcherGroup> ret = new LinkedList<>();
					ResultSet res = conn.prepAndBind("qualifiers.any_with_key", qualifier.getName()).executeQuery();
					while (res.next()) {
						ret.add(getMatcherGroup(res.getString(1), res.getInt(2)));
					}
					return ret;
				}
			}
		});
	}

	@Deprecated
	protected final void setupAliases() {
		ConfigurationSection aliases = getConfig().getConfigurationSection("aliases");
		if (aliases == null) {
			return;
		}
		tableNames = aliases.getValues(false);
	}

	private void executeStream(SQLConnection conn, InputStream str) throws SQLException, IOException {
		String deploySQL = StringUtils.readStream(str);
		Statement s = conn.getStatement();

		for (String sqlQuery : deploySQL.trim().split(";")) {
			sqlQuery = sqlQuery.trim();
			if (sqlQuery.isEmpty()) {
				continue;
			}
			sqlQuery = expandQuery(sqlQuery + ";");
			s.addBatch(sqlQuery);
		}
		s.executeBatch();
	}

	protected final void deployTables() throws PermissionBackendException {
		try (SQLConnection conn = getSQL()) {
			if (conn.hasTable("{groups}") && conn.hasTable("{qualifiers}") && conn.hasTable("{entries}")) {
				return;
			}
			InputStream databaseDumpStream = getClass().getResourceAsStream("/sql/" + dbDriver + "/deploy.sql");

			if (databaseDumpStream == null) {
				throw new Exception("Can't find appropriate database dump for used database (" + dbDriver + "). Is it bundled?");
			}

			getLogger().info("Deploying default database scheme");
			executeStream(conn, databaseDumpStream);
			setSchemaVersion(getLatestSchemaVersion());
			if (!conn.hasTable("{permissions}")) {
				initializeDefaultConfiguration();
			}
		} catch (PermissionBackendException e) {
			throw e;
		} catch (Exception e) {
			throw new PermissionBackendException("Deploying of default data failed. Please initialize database manually using " + dbDriver + ".sql", e);
		}
		getLogger().info("Database scheme deploying complete.");
	}



	public void reload() {
		while (!matcherCache.isEmpty()) {
			for (Iterator<SQLMatcherGroup> it = matcherCache.values().iterator(); it.hasNext(); ) {
				it.next().invalidate();
				it.remove();
			}
		}
		matcherCache.clear();
	}

	@Override
	public void setPersistent(boolean persist) {
	}

	@Override
	public void writeContents(Writer writer) throws IOException {
		try (SQLConnection conn = getSQL()) {
			writeTable("groups", conn, writer);
			writeTable("qualifiers", conn, writer);
			writeTable("entries", conn, writer);
		} catch (SQLException e) {
			throw new IOException(e);
		}
	}

	private void writeTable(String table, SQLConnection conn, Writer writer) throws IOException, SQLException {
		ResultSet res = conn.prep("SHOW CREATE TABLE `{" + table + "}`").executeQuery();
		if (!res.next()) {
			throw new IOException("No value for table create for table " + table);
		}
		writer.write(res.getString(2));
		writer.write(";\n");

		res = conn.prep("SELECT * FROM `{" + table + "}`").executeQuery();
		while (res.next()) {
			writer.write("INSERT INTO `{");
			writer.write(table);
			writer.write("}` VALUES (");

			for (int i = 1; i <= res.getMetaData().getColumnCount(); ++i) {
				String value = res.getString(i);
				Class<?> columnClazz;
				try {
					columnClazz = Class.forName(res.getMetaData().getColumnClassName(i));
				} catch (ClassNotFoundException e) {
					throw new IOException(e);
				}
				if (value == null) {
					value = "null";
				} else {
					if (String.class.equals(columnClazz)) {
						value = "'" + value + "'";
					}
				}
				writer.write(value);
				if (i == res.getMetaData().getColumnCount()) { // Last column
					writer.write(");\n");
				} else {
					writer.write(", ");
				}
			}
		}
		writer.write('\n');
	}

	@Override
	public void close() throws PermissionBackendException {
		if (ds != null) {
			try {
				ds.close();
			} catch (SQLException e) {
				throw new PermissionBackendException("Error while closing", e);
			}
		}
		super.close();
	}
}
