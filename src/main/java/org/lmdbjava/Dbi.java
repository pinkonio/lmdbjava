/*
 * Copyright 2016 The LmdbJava Project, http://lmdbjava.org/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lmdbjava;

import static java.util.Objects.requireNonNull;
import static jnr.ffi.Memory.allocateDirect;
import static jnr.ffi.NativeType.ADDRESS;
import jnr.ffi.Pointer;
import jnr.ffi.byref.PointerByReference;
import static org.lmdbjava.Dbi.KeyNotFoundException.MDB_NOTFOUND;
import org.lmdbjava.Env.NotOpenException;
import static org.lmdbjava.Env.SHOULD_CHECK;
import static org.lmdbjava.Library.LIB;
import static org.lmdbjava.Library.RUNTIME;
import static org.lmdbjava.MaskedFlag.mask;
import static org.lmdbjava.ResultCodeMapper.checkRc;
import org.lmdbjava.Txn.CommittedException;
import org.lmdbjava.Txn.ReadWriteRequiredException;

/**
 * LMDB Database.
 * <p>
 * All accessor and mutator methods on this class use <code>ByteBuffer</code>.
 * This is solely for end user convenience and use of these methods is strongly
 * discouraged. Instead use the more flexible buffer API and lower latency
 * operating modes available via {@link #openCursor(org.lmdbjava.Txn)}.
 *
 * @param <T> buffer type
 */
public final class Dbi<T> {

  private final Pointer dbi;
  private final Env<T> env;
  private final String name;

  Dbi(final Env<T> env, final Txn<T> txn, final String name,
      final DbiFlags... flags) throws CommittedException, LmdbNativeException,
                                      ReadWriteRequiredException {
    requireNonNull(env);
    requireNonNull(txn);
    txn.checkNotCommitted();
    txn.checkWritesAllowed();
    this.env = env;
    this.name = name;
    final int flagsMask = mask(flags);
    final Pointer dbiPtr = allocateDirect(RUNTIME, ADDRESS);
    checkRc(LIB.mdb_dbi_open(txn.ptr, name, flagsMask, dbiPtr));
    dbi = dbiPtr.getPointer(0);
  }

  /**
   * Starts a new read-write transaction and deletes the key.
   *
   * @param key key to delete from the database (not null)
   * @throws CommittedException         if already committed
   * @throws NotOpenException           if the environment is not currently open
   * @throws LmdbNativeException        if a native C error occurred
   * @throws ReadWriteRequiredException if a read-only transaction presented
   * @see #delete(Txn, ByteBuffer, ByteBuffer)
   */
  public void delete(final T key) throws
      CommittedException, LmdbNativeException,
      NotOpenException, ReadWriteRequiredException {
    try (final Txn<T> txn = env.txnWrite()) {
      delete(txn, key);
      txn.commit();
    }
  }

  /**
   * Deletes the key using the passed transaction.
   *
   * @param txn transaction handle (not null; not committed; must be R-W)
   * @param key key to delete from the database (not null)
   * @throws CommittedException         if already committed
   * @throws LmdbNativeException        if a native C error occurred
   * @throws ReadWriteRequiredException if a read-only transaction presented
   * @see #delete(Txn, ByteBuffer, ByteBuffer)
   */
  public void delete(final Txn<T> txn, final T key) throws
      CommittedException, LmdbNativeException,
      ReadWriteRequiredException {
    delete(txn, key, null);
  }

  /**
   * Removes key/data pairs from the database.
   * <p>
   * If the database does not support sorted duplicate data items
   * ({@link DbiFlags#MDB_DUPSORT}) the value parameter is ignored. If the
   * database supports sorted duplicates and the value parameter is null, all of
   * the duplicate data items for the key will be deleted. Otherwise, if the
   * data parameter is non-null only the matching data item will be deleted.
   * <p>
   * This function will throw {@link KeyNotFoundException} if the key/data pair
   * is not found.
   *
   * @param txn transaction handle (not null; not committed; must be R-W)
   * @param key key to delete from the database (not null)
   * @param val value to delete from the database (null permitted)
   * @throws CommittedException         if already committed
   * @throws LmdbNativeException        if a native C error occurred
   * @throws ReadWriteRequiredException if a read-only transaction presented
   */
  public void delete(final Txn<T> txn, final T key, final T val)
      throws
      CommittedException, LmdbNativeException,
      ReadWriteRequiredException {
    if (SHOULD_CHECK) {
      requireNonNull(txn);
      requireNonNull(key);
      txn.checkNotCommitted();
      txn.checkWritesAllowed();
    }

    txn.keyIn(key);

    if (val == null) {
      checkRc(LIB.mdb_del(txn.ptr, dbi, txn.ptrKey, null));
    } else {
      txn.valIn(val);
      checkRc(LIB.mdb_del(txn.ptr, dbi, txn.ptrKey, txn.ptrVal));
    }
  }

  /**
   * Get items from a database, moving the {@link Txn#val()} to the value.
   * <p>
   * This function retrieves key/data pairs from the database. The address and
   * length of the data associated with the specified \b key are returned in the
   * structure to which \b data refers. If the database supports duplicate keys
   * ({@link org.lmdbjava.DbiFlags#MDB_DUPSORT}) then the first data item for
   * the key will be returned. Retrieval of other items requires the use of
   * #mdb_cursor_get().
   *
   * @param txn transaction handle (not null; not committed)
   * @param key key to search for in the database (not null)
   * @return the data or null if not found
   * @throws CommittedException  if already committed
   * @throws LmdbNativeException if a native C error occurred
   */
  public T get(final Txn<T> txn, final T key)
      throws
      CommittedException, LmdbNativeException {
    if (SHOULD_CHECK) {
      requireNonNull(txn);
      requireNonNull(key);
      txn.checkNotCommitted();
    }
    txn.keyIn(key);
    final int rc = LIB.mdb_get(txn.ptr, dbi, txn.ptrKey, txn.ptrVal);
    if (rc == MDB_NOTFOUND) {
      return null;
    }
    checkRc(rc);
    txn.valOut(); // marked as out in LMDB C docs
    return txn.val();
  }

  /**
   * Obtains the name of this database.
   *
   * @return the name (may be null or empty)
   */
  public String getName() {
    return name;
  }

  /**
   * Create a cursor handle.
   * <p>
   * A cursor is associated with a specific transaction and database. A cursor
   * cannot be used when its database handle is closed. Nor when its transaction
   * has ended, except with {@link Cursor#renew(org.lmdbjava.Txn)}. It can be
   * discarded with {@link Cursor#close()}. A cursor in a write-transaction can
   * be closed before its transaction ends, and will otherwise be closed when
   * its transaction ends. A cursor in a read-only transaction must be closed
   * explicitly, before or after its transaction ends. It can be reused with
   * {@link Cursor#renew(org.lmdbjava.Txn)} before finally closing it.
   *
   * @param txn transaction handle (not null; not committed)
   * @return cursor handle
   * @throws LmdbNativeException if a native C error occurred
   * @throws CommittedException  if already committed
   */
  public Cursor<T> openCursor(final Txn<T> txn)
      throws LmdbNativeException, CommittedException {
    if (SHOULD_CHECK) {
      requireNonNull(txn);
      txn.checkNotCommitted();
    }
    final PointerByReference ptr = new PointerByReference();
    checkRc(LIB.mdb_cursor_open(txn.ptr, dbi, ptr));
    return new Cursor<>(ptr.getValue(), txn);
  }

  /**
   * Starts a new read-write transaction and puts the key/data pair.
   *
   * @param key key to store in the database (not null)
   * @param val value to store in the database (not null)
   * @throws CommittedException         if already committed
   * @throws NotOpenException           if the environment is not currently open
   * @throws LmdbNativeException        if a native C error occurred
   * @throws ReadWriteRequiredException if a read-only transaction presented
   * @see Dbi#put(Txn, ByteBuffer, ByteBuffer, PutFlags...)
   */
  public void put(final T key, final T val) throws
      CommittedException, LmdbNativeException,
      NotOpenException, ReadWriteRequiredException {
    try (final Txn<T> txn = env.txnWrite()) {
      put(txn, key, val);
      txn.commit();
    }
  }

  /**
   * Store a key/value pair in the database.
   * <p>
   * This function stores key/data pairs in the database. The default behavior
   * is to enter the new key/data pair, replacing any previously existing key if
   * duplicates are disallowed, or adding a duplicate data item if duplicates
   * are allowed ({@link DbiFlags#MDB_DUPSORT}).
   *
   * @param txn   transaction handle (not null; not committed; must be R-W)
   * @param key   key to store in the database (not null)
   * @param val   value to store in the database (not null)
   * @param flags Special options for this operation
   * @throws CommittedException         if already committed
   * @throws LmdbNativeException        if a native C error occurred
   * @throws ReadWriteRequiredException if a read-only transaction presented
   */
  public void put(final Txn<T> txn, final T key, final T val,
                  final PutFlags... flags)
      throws CommittedException, LmdbNativeException,
             ReadWriteRequiredException {
    if (SHOULD_CHECK) {
      requireNonNull(txn);
      requireNonNull(key);
      requireNonNull(val);
      txn.checkNotCommitted();
      txn.checkWritesAllowed();
    }
    txn.keyIn(key);
    txn.valIn(val);
    final int mask = mask(flags);
    checkRc(LIB.mdb_put(txn.ptr, dbi, txn.ptrKey, txn.ptrVal, mask));
    txn.valOut(); // marked as in,out in LMDB C docs
  }

  /**
   * The specified DBI was changed unexpectedly.
   */
  public static final class BadDbiException extends LmdbNativeException {

    private static final long serialVersionUID = 1L;
    static final int MDB_BAD_DBI = -30_780;

    BadDbiException() {
      super(MDB_BAD_DBI, "The specified DBI was changed unexpectedly");
    }
  }

  /**
   * Unsupported size of key/DB name/data, or wrong DUPFIXED size.
   */
  public static final class BadValueSizeException extends LmdbNativeException {

    private static final long serialVersionUID = 1L;
    static final int MDB_BAD_VALSIZE = -30_781;

    BadValueSizeException() {
      super(MDB_BAD_VALSIZE,
            "Unsupported size of key/DB name/data, or wrong DUPFIXED size");
    }
  }

  /**
   * Environment maxdbs reached.
   */
  public static final class DbFullException extends LmdbNativeException {

    private static final long serialVersionUID = 1L;
    static final int MDB_DBS_FULL = -30_791;

    DbFullException() {
      super(MDB_DBS_FULL, "Environment maxdbs reached");
    }
  }

  /**
   * Operation and DB incompatible, or DB type changed.
   * <p>
   * This can mean:
   * <ul>
   * <li>The operation expects an MDB_DUPSORT / MDB_DUPFIXED database.</li>
   * <li>Opening a named DB when the unnamed DB has MDB_DUPSORT /
   * MDB_INTEGERKEY.</li>
   * <li>Accessing a data record as a database, or vice versa.</li>
   * <li>The database was dropped and recreated with different flags.</li>
   * </ul>
   */
  public static final class IncompatibleException extends LmdbNativeException {

    private static final long serialVersionUID = 1L;
    static final int MDB_INCOMPATIBLE = -30_784;

    IncompatibleException() {
      super(MDB_INCOMPATIBLE,
            "Operation and DB incompatible, or DB type changed");
    }
  }

  /**
   * Key/data pair already exists.
   */
  public static final class KeyExistsException extends LmdbNativeException {

    private static final long serialVersionUID = 1L;
    static final int MDB_KEYEXIST = -30_799;

    KeyExistsException() {
      super(MDB_KEYEXIST, "key/data pair already exists");
    }
  }

  /**
   * Key/data pair not found (EOF).
   */
  public static final class KeyNotFoundException extends LmdbNativeException {

    private static final long serialVersionUID = 1L;
    static final int MDB_NOTFOUND = -30_798;

    KeyNotFoundException() {
      super(MDB_NOTFOUND, "key/data pair not found (EOF)");
    }
  }

  /**
   * Database contents grew beyond environment mapsize.
   */
  public static final class MapResizedException extends LmdbNativeException {

    private static final long serialVersionUID = 1L;
    static final int MDB_MAP_RESIZED = -30_785;

    MapResizedException() {
      super(MDB_MAP_RESIZED, "Database contents grew beyond environment mapsize");
    }
  }
}
