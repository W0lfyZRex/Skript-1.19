/**
 *   This file is part of Skript.
 *
 *  Skript is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Skript is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Skript.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright Peter Güttinger, SkriptLang team and contributors
 */
package ch.njol.skript.variables;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;

import lib.PatPeter.SQLibrary.Database;
import lib.PatPeter.SQLibrary.DatabaseException;
import lib.PatPeter.SQLibrary.MySQL;
import lib.PatPeter.SQLibrary.SQLibrary;
import lib.PatPeter.SQLibrary.SQLite;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.eclipse.jdt.annotation.Nullable;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.Serializer;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.Task;
import ch.njol.skript.util.Timespan;
import ch.njol.util.SynchronizedReference;

/**
 * TODO create a metadata table to store some properties (e.g. Skript version, Yggdrasil version) -- but what if some
 * variables cannot be converted? move them to a different table?
 * TODO create my own database connector or find a better one
 *
 * @author Peter Güttinger
 */
public class DatabaseStorage extends VariablesStorage {
	
	public final static int MAX_VARIABLE_NAME_LENGTH = 380, // MySQL: 767 bytes max; cannot set max bytes, only max characters
			MAX_CLASS_CODENAME_LENGTH = 50, // checked when registering a class
			MAX_VALUE_SIZE = 10000;
	
	private final static String OLD_TABLE_NAME = "variables";
	
	private final static String SELECT_ORDER = "name, type, value, rowid";
	
	public static enum Type {
		
		MYSQL("CREATE TABLE IF NOT EXISTS %s (" + "rowid        BIGINT  NOT NULL  AUTO_INCREMENT  PRIMARY KEY," + "name         VARCHAR(" + MAX_VARIABLE_NAME_LENGTH + ")  NOT NULL  UNIQUE," + "type         VARCHAR(" + MAX_CLASS_CODENAME_LENGTH + ")," + "value        BLOB(" + MAX_VALUE_SIZE + ")," + "update_guid  CHAR(36)  NOT NULL" + ") CHARACTER SET ucs2 COLLATE ucs2_bin") {// MySQL treats UTF16 as 4 byte charset, resulting in a short max name length. UCS2 uses 2 bytes.
			
			@Override
			@Nullable
			protected Object initialise(final DatabaseStorage s, final SectionNode n) {
				final String host = s.getValue(n, "host");
				final Integer port = s.getValue(n, "port", Integer.class);
				final String user = s.getValue(n, "user");
				final String password = s.getValue(n, "password");
				final String database = s.getValue(n, "database");
				s.setTableName(n.get("table", "variables21"));
				if (host == null || port == null || user == null || password == null || database == null)
					return null;
				return new MySQL(SkriptLogger.LOGGER, "[Skript]", host, port, database, user, password);
			}
		},
		SQLITE("CREATE TABLE IF NOT EXISTS %s (" + "name         VARCHAR(" + MAX_VARIABLE_NAME_LENGTH + ")  NOT NULL  PRIMARY KEY," + "type         VARCHAR(" + MAX_CLASS_CODENAME_LENGTH + ")," + "value        BLOB(" + MAX_VALUE_SIZE + ")," + "update_guid  CHAR(36)  NOT NULL" + ")") {// SQLite uses Unicode exclusively
			
			@Override
			@Nullable
			protected Object initialise(final DatabaseStorage s, final SectionNode config) {
				final File f = s.file;
				if (f == null)
					return null;
				s.setTableName(config.get("table", "variables21"));
				final String name = f.getName();
				assert name.endsWith(".db");
				return new SQLite(SkriptLogger.LOGGER, "[Skript]", f.getParent(),
						name.substring(0, name.length() - ".db".length()));
			}
		};
		
		final String createQuery;
		
		private Type(final String createQuery) {
			this.createQuery = createQuery;
		}
		
		@Nullable
		protected abstract Object initialise(DatabaseStorage s, SectionNode config);
	}
	
	private final Type type;
	private String tableName;
	@Nullable
	private String formattedCreateQuery;
	
	final SynchronizedReference<Database> db = new SynchronizedReference<>(null);
	
	private boolean monitor = false;
	long monitor_interval;
	
	private final static String guid = "" + UUID.randomUUID().toString();
	
	/**
	 * The delay between transactions in milliseconds.
	 */
	private final static long TRANSACTION_DELAY = 50;
	
	DatabaseStorage(final String name, final Type type) {
		super(name);
		this.type = type;
		this.tableName = "variables21";
	}
	
	public String getTableName() {
		return tableName;
	}
	
	public void setTableName(String tableName) {
		this.tableName = tableName;
	}
	
	/**
	 * Retrieve the create query with the tableName in it
	 * 
	 * @return the create query with the tableName in it (%s -> tableName)
	 */
	@Nullable
	public String getFormattedCreateQuery() {
		if (formattedCreateQuery == null) {
			formattedCreateQuery = String.format(type.createQuery, tableName);
		}
		return formattedCreateQuery;
	}
	
	/**
	 * Doesn't lock the database for reading (it's not used anywhere else, and locking while loading will interfere with
	 * loaded variables being deleted by
	 * {@link Variables#variableLoaded(String, Object, VariablesStorage)}).
	 */
	@Override
	protected boolean load_i(final SectionNode n) {
		synchronized (db) {
			final Plugin p = Bukkit.getPluginManager().getPlugin("SQLibrary");
			if (p == null || !(p instanceof SQLibrary)) {
				Skript.error("You need the plugin SQLibrary in order to use a database with Skript. You can download the latest version from http://dev.bukkit.org/server-mods/sqlibrary/files/");
				return false;
			}
			
			final Boolean monitor_changes = getValue(n, "monitor changes", Boolean.class);
			final Timespan monitor_interval = getValue(n, "monitor interval", Timespan.class);
			if (monitor_changes == null || monitor_interval == null)
				return false;
			monitor = monitor_changes;
			this.monitor_interval = monitor_interval.getMilliSeconds();
			
			final Database db;
			try {
				final Object o = type.initialise(this, n);
				if (o == null)
					return false;
				this.db.set(db = (Database) o);
			} catch (final RuntimeException e) {
				if (e instanceof DatabaseException) {// not in a catch clause to not produce a ClassNotFoundException when this class is loaded and SQLibrary is not present
					Skript.error(e.getLocalizedMessage());
					return false;
				}
				throw e;
			}
			
			SkriptLogger.setNode(null);
			
			if (!connect(true))
				return false;
			
			try {
				final boolean hasOldTable = db.isTable(OLD_TABLE_NAME);
				final boolean hadNewTable = db.isTable(getTableName());
				
				if (getFormattedCreateQuery() == null) {
					Skript.error("Could not create the variables table in the database. The query to create the variables table '" + tableName + "' in the database '" + databaseName + "' is null.");
					return false;
				}
				
				try {
					db.query(getFormattedCreateQuery());
				} catch (final SQLException e) {
					Skript.error("Could not create the variables table '" + tableName + "' in the database '" + databaseName + "': " + e.getLocalizedMessage() + ". " + "Please create the table yourself using the following query: " + String.format(type.createQuery, tableName).replace(",", ", ").replaceAll("\\s+", " "));
					return false;
				}
				
				if (!prepareQueries()) {
					return false;
				}
				
				// old
				// Table name support was added after the verison that used the legacy database format
				if (hasOldTable && !tableName.equals("variables")) {
					final ResultSet r1 = db.query("SELECT " + SELECT_ORDER + " FROM " + OLD_TABLE_NAME);
					assert r1 != null;
					try {
						oldLoadVariables(r1, hadNewTable);
					} finally {
						r1.close();
					}
				}
				
				// new
				final ResultSet r2 = db.query("SELECT " + SELECT_ORDER + " FROM " + getTableName());
				assert r2 != null;
				try {
					loadVariables(r2);
				} finally {
					r2.close();
				}
				
				// store old variables in new table and delete the old table
				if (hasOldTable) {
					if (!hadNewTable) {
						Skript.info("[2.1] Updating the database '" + databaseName + "' to the new format...");
						try {
							Variables.getReadLock().lock();
							for (final Entry<String, Object> v : Variables.getVariablesHashMap().entrySet()) {
								if (accept(v.getKey())) {// only one database was possible, so only checking this database is correct
									@SuppressWarnings("null")
									final SerializedVariable var = Variables.serialize(v.getKey(), v.getValue());
									final SerializedVariable.Value d = var.value;
									save(var.name, d == null ? null : d.type, d == null ? null : d.data);
								}
							}
							Skript.info("Updated and transferred " + Variables.getVariablesHashMap().size() + " variables to the new table.");
						} finally {
							Variables.getReadLock().unlock();
						}
					}
					db.query("DELETE FROM " + OLD_TABLE_NAME + " WHERE value IS NULL");
					db.query("DELETE FROM old USING " + OLD_TABLE_NAME + " AS old, " + getTableName() + " AS new WHERE old.name = new.name");
					final ResultSet r = db.query("SELECT * FROM " + OLD_TABLE_NAME + " LIMIT 1");
					try {
						if (r.next()) {// i.e. the old table is not empty
							Skript.error("Could not successfully convert & transfer all variables to the new table in the database '" + databaseName + "'. " + "Variables that could not be transferred are left in the old table and Skript will reattempt to transfer them whenever it starts until the old table is empty or is manually deleted. " + "Please note that variables recreated by scripts will count as converted and will be removed from the old table on the next restart.");
						} else {
							boolean error = false;
							try {
								disconnect(); // prevents SQLITE_LOCKED error
								connect();
								db.query("DROP TABLE " + OLD_TABLE_NAME);
							} catch (final SQLException e) {
								Skript.error("There was an error deleting the old variables table from the database '" + databaseName + "', please delete it yourself: " + e.getLocalizedMessage());
								error = true;
							}
							if (!error)
								Skript.info("Successfully deleted the old variables table from the database '" + databaseName + "'.");
							if (!hadNewTable)
								Skript.info("Database '" + databaseName + "' successfully updated.");
						}
					} finally {
						r.close();
					}
				}
			} catch (final SQLException e) {
				sqlException(e);
				return false;
			}
			
			// periodically executes queries to keep the collection alive
			Skript.newThread(new Runnable() {
				
				@Override
				public void run() {
					while (!closed) {
						synchronized (DatabaseStorage.this.db) {
							try {
								final Database db = DatabaseStorage.this.db.get();
								if (db != null)
									db.query("SELECT * FROM " + getTableName() + " LIMIT 1");
							} catch (final SQLException e) {
							}
						}
						try {
							Thread.sleep(1000 * 10);
						} catch (final InterruptedException e) {
						}
					}
				}
			}, "Skript database '" + databaseName + "' connection keep-alive thread").start();
			
			return true;
		}
	}
	
	@Override
	protected void allLoaded() {
		Skript.debug("Database " + databaseName + " loaded. Queue size = " + changesQueue.size());
		
		// start committing thread. Its first execution will also commit the first batch of changed variables.
		/**Skript.newThread(new Runnable() {
			
			@Override
			public void run() {
				long lastCommit;
				while (!closed) {
					synchronized (db) {
						final Database db = DatabaseStorage.this.db.get();
						try {
							if (db != null)
								db.getConnection().commit();
						} catch (final SQLException e) {
							sqlException(e);
						}
						lastCommit = System.currentTimeMillis();
					}
					try {
						Thread.sleep(Math.max(0, lastCommit + TRANSACTION_DELAY - System.currentTimeMillis()));
					} catch (final InterruptedException e) {
					}
				}
			}
		}, "Skript database '" + databaseName + "' transaction committing thread").start();*/
		
		if (monitor) {
			Skript.newThread(new Runnable() {
				
				@Override
				public void run() {
					try { // variables were just downloaded, not need to check for modifications straight away
						Thread.sleep(monitor_interval);
					} catch (final InterruptedException e1) {
					}
					
					long lastWarning = Long.MIN_VALUE;
					final int WARING_INTERVAL = 2;
					
					while (!closed) {
						final long next = System.currentTimeMillis() + monitor_interval;
						checkDatabase();
						final long now = System.currentTimeMillis();
						if (next < now) {
							// TODO don't print this message when Skript loads (because scripts are loaded after variables and take some time)
							if (lastWarning + WARING_INTERVAL * 1000 < now) {
								Skript.warning("Cannot load variables from the database fast enough (loading took " + ((now - next + monitor_interval) / 1000.) + "s, monitor interval = " + (monitor_interval / 1000.) + "s). " + "Please increase your monitor interval or reduce usage of variables. " + "(this warning will be repeated at most once every " + WARING_INTERVAL + " seconds)");
								lastWarning = now;
							}
						}
						while (System.currentTimeMillis() < next) {
							try {
								Thread.sleep(next - System.currentTimeMillis());
							} catch (final InterruptedException e) {
							}
						}
					}
				}
			}, "Skript database '" + databaseName + "' monitor thread").start();
		}
		
	}
	
	@Override
	protected boolean requiresFile() {
		return type == Type.SQLITE;
	}
	
	@Override
	protected File getFile(String file) {
		if (!file.endsWith(".db"))
			file = file + ".db"; // required by SQLibrary
		return new File(file);
	}
	
	@Override
	protected boolean connect() {
		return connect(false);
	}
	
	private final boolean connect(final boolean first) {
		synchronized (db) {
			// isConnected doesn't work in SQLite
//			if (db.isConnected())
//				return;
			final Database db = this.db.get();
			if (db == null || !db.open()) {
				if (first)
					Skript.error("Cannot connect to the database '" + databaseName + "'! Please make sure that all settings are correct" + (type == Type.MYSQL ? " and that the database software is running" : "") + ".");
				else
					Skript.exception("Cannot reconnect to the database '" + databaseName + "'!");
				return false;
			}
			try {
				db.getConnection().setAutoCommit(true);
			} catch (final SQLException e) {
				sqlException(e);
				return false;
			}
			return true;
		}
	}
	
	/**
	 * (Re)creates prepared statements as they get closed as well when closing the connection
	 *
	 * @return
	 */
	private boolean prepareQueries() {
		synchronized (db) {
			final Database db = this.db.get();
			assert db != null;
			try {
				try {
					if (writeQuery != null)
						writeQuery.close();
				} catch (final SQLException e) {
				}
				writeQuery = db.prepare("REPLACE INTO " + getTableName() + " (name, type, value, update_guid) VALUES (?, ?, ?, ?)");
				
				try {
					if (deleteQuery != null)
						deleteQuery.close();
				} catch (final SQLException e) {
				}
				deleteQuery = db.prepare("DELETE FROM " + getTableName() + " WHERE name = ? AND value IS NULL AND type IS NULL AND update_guid = ?");
				
				try {
					if (monitorQuery != null)
						monitorQuery.close();
				} catch (final SQLException e) {
				}
				monitorQuery = db.prepare("SELECT " + SELECT_ORDER + " FROM " + getTableName() + " WHERE rowid > ? AND update_guid != ?");
				try {
					if (monitorCleanUpQuery != null)
						monitorCleanUpQuery.close();
				} catch (final SQLException e) {
				}
				monitorCleanUpQuery = db.prepare("DELETE FROM " + getTableName() + " WHERE value IS NULL AND rowid < ?");
			} catch (final SQLException e) {
				Skript.exception(e, "Could not prepare queries for the database '" + databaseName + "': " + e.getLocalizedMessage());
				return false;
			}
		}
		return true;
	}
	
	@Override
	protected void disconnect() {
		synchronized (db) {
			final Database db = this.db.get();
//			if (!db.isConnected())
//				return;
			if (db != null)
				db.close();
		}
	}
	
	/**
	 * Params: name, type, value, GUID
	 * <p>
	 * Writes a variable to the database
	 */
	@Nullable
	private PreparedStatement writeQuery;
	/**
	 * Params: name
	 * <p>
	 * Deletes a variable from the database
	 */
	@Nullable
	private PreparedStatement deleteQuery;
	/**
	 * Params: rowID, GUID
	 * <p>
	 * Selects changed rows. values in order: {@value #SELECT_ORDER}
	 */
	@Nullable
	private PreparedStatement monitorQuery;
	/**
	 * Params: rowID
	 * <p>
	 * Deletes null variables from the database older than the given value
	 */
	@Nullable
	PreparedStatement monitorCleanUpQuery;
	
	@Override
	protected boolean save(final String name, final @Nullable String type, final @Nullable byte[] value) {
		synchronized (db) {
			// REMIND get the actual maximum size from the database
			if (name.length() > MAX_VARIABLE_NAME_LENGTH)
				Skript.error("The name of the variable {" + name + "} is too long to be saved in a database (length: " + name.length() + ", maximum allowed: " + MAX_VARIABLE_NAME_LENGTH + ")! It will be truncated and won't bet available under the same name again when loaded.");
			if (value != null && value.length > MAX_VALUE_SIZE)
				Skript.error("The variable {" + name + "} cannot be saved in the database as its value's size (" + value.length + ") exceeds the maximum allowed size of " + MAX_VALUE_SIZE + "! An attempt to save the variable will be made nonetheless.");
			try {
				if (type == null) {
					assert value == null;
					final PreparedStatement writeQuery = this.writeQuery;
					assert writeQuery != null;
					writeQuery.setString(1, name);
					writeQuery.setString(2, type);
					writeQuery.setBytes(3, value);
					writeQuery.setString(4, guid);
					writeQuery.executeUpdate();
					/*Bukkit.getScheduler().runTaskLater(Skript.getInstance(), new Runnable() {
						@Override
						public void run() {
							try {
								final PreparedStatement deleteQuery = DatabaseStorage.this.deleteQuery;
								assert deleteQuery != null;
								deleteQuery.setString(1, name);
								deleteQuery.setString(2, guid);
								deleteQuery.executeUpdate();
							} catch (final SQLException e1) {
								//SkriptLogger.log(Level.WARNING, "Failed to delete row containing variable '" + name + "' in the database '" + databaseName + "': " + e1.getLocalizedMessage());
								sqlException(e1);
								return;
							}
						}
					}, 41L);*/
				} else {
					int i = 1;
					final PreparedStatement writeQuery = this.writeQuery;
					assert writeQuery != null;
					writeQuery.setString(i++, name);
					writeQuery.setString(i++, type);
					writeQuery.setBytes(i++, value); // SQLite doesn't support setBlob
					writeQuery.setString(i++, guid);
					writeQuery.executeUpdate();
				}
			} catch (final SQLException e) {
				sqlException(e);
				save1(name, type, value);
			}
		}
		return true;
	}
	
	protected boolean save1(final String name, final @Nullable String type, final @Nullable byte[] value) {
		synchronized (db) {
			// REMIND get the actual maximum size from the database
			if (name.length() > MAX_VARIABLE_NAME_LENGTH)
				Skript.error("The name of the variable {" + name + "} is too long to be saved in a database (length: " + name.length() + ", maximum allowed: " + MAX_VARIABLE_NAME_LENGTH + ")! It will be truncated and won't bet available under the same name again when loaded.");
			if (value != null && value.length > MAX_VALUE_SIZE)
				Skript.error("The variable {" + name + "} cannot be saved in the database as its value's size (" + value.length + ") exceeds the maximum allowed size of " + MAX_VALUE_SIZE + "! An attempt to save the variable will be made nonetheless.");
			try {
				if (type == null) {
					assert value == null;
					final PreparedStatement writeQuery = this.writeQuery;
					assert writeQuery != null;
					writeQuery.setString(1, name);
					writeQuery.setString(2, type);
					writeQuery.setBytes(3, value);
					writeQuery.setString(4, guid);
					writeQuery.executeUpdate();
					/*Bukkit.getScheduler().runTaskLater(Skript.getInstance(), new Runnable() {
						@Override
						public void run() {
							try {
								final PreparedStatement deleteQuery = DatabaseStorage.this.deleteQuery;
								assert deleteQuery != null;
								deleteQuery.setString(1, name);
								deleteQuery.setString(2, guid);
								deleteQuery.executeUpdate();
							} catch (final SQLException e1) {
								//SkriptLogger.log(Level.WARNING, "Failed to delete row containing variable '" + name + "' in the database '" + databaseName + "': " + e1.getLocalizedMessage());
								sqlException(e1);
								return;
							}
						}
					}, 41L);*/
				} else {
					int i = 1;
					final PreparedStatement writeQuery = this.writeQuery;
					assert writeQuery != null;
					writeQuery.setString(i++, name);
					writeQuery.setString(i++, type);
					writeQuery.setBytes(i++, value); // SQLite doesn't support setBlob
					writeQuery.setString(i++, guid);
					writeQuery.executeUpdate();
				}
			} catch (final SQLException e) {
				sqlException(e);
				save2(name, type, value);
			} finally {
				errorRecovered(name, type, value);
			}
		}
		return true;
	}

	protected boolean save2(final String name, final @Nullable String type, final @Nullable byte[] value) {
		synchronized (db) {
			// REMIND get the actual maximum size from the database
			if (name.length() > MAX_VARIABLE_NAME_LENGTH)
				Skript.error("The name of the variable {" + name + "} is too long to be saved in a database (length: " + name.length() + ", maximum allowed: " + MAX_VARIABLE_NAME_LENGTH + ")! It will be truncated and won't bet available under the same name again when loaded.");
			if (value != null && value.length > MAX_VALUE_SIZE)
				Skript.error("The variable {" + name + "} cannot be saved in the database as its value's size (" + value.length + ") exceeds the maximum allowed size of " + MAX_VALUE_SIZE + "! An attempt to save the variable will be made nonetheless.");
			try {
				if (type == null) {
					assert value == null;
					final PreparedStatement writeQuery = this.writeQuery;
					assert writeQuery != null;
					writeQuery.setString(1, name);
					writeQuery.setString(2, type);
					writeQuery.setBytes(3, value);
					writeQuery.setString(4, guid);
					writeQuery.executeUpdate();
					/*Bukkit.getScheduler().runTaskLater(Skript.getInstance(), new Runnable() {
						@Override
						public void run() {
							try {
								final PreparedStatement deleteQuery = DatabaseStorage.this.deleteQuery;
								assert deleteQuery != null;
								deleteQuery.setString(1, name);
								deleteQuery.setString(2, guid);
								deleteQuery.executeUpdate();
							} catch (final SQLException e1) {
								//SkriptLogger.log(Level.WARNING, "Failed to delete row containing variable '" + name + "' in the database '" + databaseName + "': " + e1.getLocalizedMessage());
								sqlException(e1);
								return;
							}
						}
					}, 41L);*/
				} else {
					int i = 1;
					final PreparedStatement writeQuery = this.writeQuery;
					assert writeQuery != null;
					writeQuery.setString(i++, name);
					writeQuery.setString(i++, type);
					writeQuery.setBytes(i++, value); // SQLite doesn't support setBlob
					writeQuery.setString(i++, guid);
					writeQuery.executeUpdate();
				}
			} catch (final SQLException e) {
				sqlException(e);
			} finally {
				errorRecovered(name, type, value);
			}
		}
		return true;
	}

	@Override
	public void close() {
		synchronized (db) {
			super.close();
			final Database db = this.db.get();
			if (db != null) {
				/**try {
					db.getConnection().commit();
				} catch (final SQLException e) {
					sqlException(e);
				}*/
				db.close();
				this.db.set(null);
			}
		}
	}
	
	long lastRowID = -1;
	int interval = 0;
	
	protected void checkDatabase() {
		try {
			final long lastRowID; // local variable as this is used to clean the database below
			ResultSet r = null;
			//final long first = System.currentTimeMillis();
			try {
				synchronized (db) {
					if (closed || db.get() == null)
						return;
					lastRowID = this.lastRowID;
					final PreparedStatement monitorQuery = this.monitorQuery;
					assert monitorQuery != null; // Throw an error if there is no monitorQuery so it doesn't trigger the "finally" block below so that it doesn't close the result set
					monitorQuery.setLong(1, lastRowID);
					monitorQuery.setString(2, guid);
					monitorQuery.execute();
					r = monitorQuery.getResultSet();
					assert r != null; // Same thing here but with the result set
				}
				//final long second = System.currentTimeMillis();
				//SkriptLogger.log(Level.WARNING, "Database '" + databaseName + "' checked in " + (second - first) + "ms");
				if (!closed)
					loadVariables(r); // r has to be set here since it would've thrown an error right before if it was null
			} finally {
				if (r != null) // Again check just in case
					r.close(); // Close the result set
			}
			
			/*if (!closed&&Skript.getInstance().isEnabled()) {
				interval++;
				if(interval>49){
					interval = 0;
					SkriptLogger.log(Level.WARNING, "Cleaning");
					new Task(Skript.getInstance(), (long) Math.ceil(2. * monitor_interval / 50) + 100, true) { // 2 times the interval + 5 seconds
						
						@Override
						public void run() {
							if(closed||!Skript.getInstance().isEnabled()) return;
							try {
								synchronized (db) {
									if (db.get() == null)
										return;
									final PreparedStatement monitorCleanUpQuery = DatabaseStorage.this.monitorCleanUpQuery;
									assert monitorCleanUpQuery != null;
									monitorCleanUpQuery.setLong(1, lastRowID);
									monitorCleanUpQuery.executeUpdate();
								}
							} catch (final SQLException e) {
								//SkriptLogger.log(Level.WARNING, "Error cleaning up the database");
								sqlException(e);
							}
						}
					};
				}
			}*/
		} catch (final SQLException e) {
			sqlException(e);
		}
	}
	
//	private final static class VariableInfo {
//		final String name;
//		final byte[] value;
//		final ClassInfo<?> ci;
//
//		public VariableInfo(final String name, final byte[] value, final ClassInfo<?> ci) {
//			this.name = name;
//			this.value = value;
//			this.ci = ci;
//		}
//	}

//	final static LinkedList<VariableInfo> syncDeserializing = new LinkedList<VariableInfo>();
	
	/**
	 * Doesn't lock the database - {@link #save(String, String, byte[])} does that // what?
	 */
	private void loadVariables(final ResultSet r) throws SQLException {
//		assert !Thread.holdsLock(db);
//		synchronized (syncDeserializing) {
		
		//final long first = System.currentTimeMillis();
		final SQLException e = Task.callSync(new Callable<SQLException>() {
			@Override
			@Nullable
			public SQLException call() throws Exception {
				try {
					while (r.next()) {
						int i = 1;
						final String name = r.getString(i++);
						if (name == null) {
							Skript.error("Variable with NULL name found in the database '" + databaseName + "', ignoring it");
							continue;
						}
						final String type = r.getString(i++);
						final byte[] value = r.getBytes(i++); // Blob not supported by SQLite
						lastRowID = r.getLong(i++);
						if (value == null) {
							if (type == null) {
								//SkriptLogger.log(Level.WARNING, "Variable '" + name + "' has no value or type in the database '" + databaseName + "', deleting it");
								Variables.variableLoaded(name, null, DatabaseStorage.this);
							}
						} else {
							final ClassInfo<?> c = Classes.getClassInfoNoError(type);
							@SuppressWarnings("unused")
							Serializer<?> s;
							if (c == null || (s = c.getSerializer()) == null) {
								Skript.error("Cannot load the variable {" + name + "} from the database '" + databaseName + "', because the type '" + type + "' cannot be recognised or cannot be stored in variables");
								continue;
							}
//					if (s.mustSyncDeserialization()) {
//						syncDeserializing.add(new VariableInfo(name, value, c));
//					} else {
							final Object d = Classes.deserialize(c, value);
							if (d == null) {
								Skript.error("Cannot load the variable {" + name + "} from the database '" + databaseName + "', because it cannot be loaded as " + c.getName().withIndefiniteArticle());
								continue;
							}
							Variables.variableLoaded(name, d, DatabaseStorage.this);
//					}
						}
					}
				} catch (final SQLException e) {
					return e;
				}
				return null;
			}
		});
		//final long second = System.currentTimeMillis();
		//SkriptLogger.log(Level.WARNING, "Loaded " + Variables.getVariables().size() + " variables in " + (second - first) + "ms");
		if (e != null)
			throw e;
		
//			if (!syncDeserializing.isEmpty()) {
//				Task.callSync(new Callable<Void>() {
//					@Override
//					@Nullable
//					public Void call() throws Exception {
//						synchronized (syncDeserializing) {
//							for (final VariableInfo o : syncDeserializing) {
//								final Object d = Classes.deserialize(o.ci, o.value);
//								if (d == null) {
//									Skript.error("Cannot load the variable {" + o.name + "} from the database " + databaseName + ", because it cannot be loaded as a " + o.ci.getName());
//									continue;
//								}
//								Variables.variableLoaded(o.name, d, DatabaseStorage.this);
//							}
//							syncDeserializing.clear();
//							return null;
//						}
//					}
//				});
//			}
//		}
	}
	
//	private final static class OldVariableInfo {
//		final String name;
//		final String value;
//		final ClassInfo<?> ci;
//
//		public OldVariableInfo(final String name, final String value, final ClassInfo<?> ci) {
//			this.name = name;
//			this.value = value;
//			this.ci = ci;
//		}
//	}
	
//	final static LinkedList<OldVariableInfo> oldSyncDeserializing = new LinkedList<OldVariableInfo>();
	
	@Deprecated
	private void oldLoadVariables(final ResultSet r, final boolean hadNewTable) throws SQLException {
//		synchronized (oldSyncDeserializing) {
		
		final VariablesStorage temp = new VariablesStorage(databaseName + " old variables table") {
			
			@Override
			protected boolean save(final String name, @Nullable final String type, @Nullable final byte[] value) {
				assert type == null : name + "; " + type;
				return true;
			}
			
			@Override
			boolean accept(@Nullable final String var) {
				assert false;
				return false;
			}
			
			@Override
			protected boolean requiresFile() {
				assert false;
				return false;
			}
			
			@Override
			protected boolean load_i(final SectionNode n) {
				assert false;
				return false;
			}
			
			@Override
			protected File getFile(final String file) {
				assert false;
				return new File(file);
			}
			
			@Override
			protected void disconnect() {
				assert false;
			}
			
			@Override
			protected boolean connect() {
				assert false;
				return false;
			}
			
			@Override
			protected void allLoaded() {
				assert false;
			}
		};
		
		final SQLException e = Task.callSync(new Callable<SQLException>() {
			
			@SuppressWarnings("null")
			@Override
			@Nullable
			public SQLException call() throws Exception {
				try {
					while (r.next()) {
						int i = 1;
						final String name = r.getString(i++);
						if (name == null) {
							Skript.error("Variable with NULL name found in the database, ignoring it");
							continue;
						}
						final String type = r.getString(i++);
						final String value = r.getString(i++);
						lastRowID = r.getLong(i++);
						if (type == null || value == null) {
							Variables.variableLoaded(name, null, hadNewTable ? temp : DatabaseStorage.this);
						} else {
							final ClassInfo<?> c = Classes.getClassInfoNoError(type);
							Serializer<?> s;
							if (c == null || (s = c.getSerializer()) == null) {
								Skript.error("Cannot load the variable {" + name + "} from the database, because the type '" + type + "' cannot be recognised or not stored in variables");
								continue;
							}
//					if (s.mustSyncDeserialization()) {
//						oldSyncDeserializing.add(new OldVariableInfo(name, value, c));
//					} else {
							final Object d = s.deserialize(value);
							if (d == null) {
								Skript.error("Cannot load the variable {" + name + "} from the database, because '" + value + "' cannot be parsed as a " + type);
								continue;
							}
							Variables.variableLoaded(name, d, DatabaseStorage.this);
//					}
						}
					}
				} catch (final SQLException e) {
					return e;
				}
				return null;
			}
		});
		if (e != null)
			throw e;
		
//			if (!oldSyncDeserializing.isEmpty()) {
//				Task.callSync(new Callable<Void>() {
//					@Override
//					@Nullable
//					public Void call() throws Exception {
//						synchronized (oldSyncDeserializing) {
//							for (final OldVariableInfo o : oldSyncDeserializing) {
//								final Serializer<?> s = o.ci.getSerializer();
//								if (s == null) {
//									assert false : o.ci;
//									continue;
//								}
//								final Object d = s.deserialize(o.value);
//								if (d == null) {
//									Skript.error("Cannot load the variable {" + o.name + "} from the database, because '" + o.value + "' cannot be parsed as a " + o.ci.getCodeName());
//									continue;
//								}
//								Variables.variableLoaded(o.name, d, DatabaseStorage.this);
//							}
//							oldSyncDeserializing.clear();
//							return null;
//						}
//					}
//				});
//			}
//		}
	}
	
	void sqlException(final SQLException e) {
		Skript.error("database error: " + e.getLocalizedMessage());
		if (Skript.testing())
			e.printStackTrace();
		prepareQueries(); // a query has to be recreated after an error
	}

	void errorRecovered(final String name, final @Nullable String type, final @Nullable byte[] value) {
		Skript.error("Resolved database error and resaved the variable '" + name + "'.");
	}
	
}
