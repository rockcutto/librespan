/*
 * Copyright (c) 2012-2021 CommonsWare, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.siacs.conversations.persistance

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileNotFoundException

object SQLCipherUtils {
  /**
   * The detected state of the database, based on whether we can open it
   * without a passphrase.
   */
  enum class State {
    DOES_NOT_EXIST, UNENCRYPTED, ENCRYPTED, ENCRYPTED_DEFAULT
  }

  fun canOpen(dbPath: File, passphrase: String): Boolean {
    if (!dbPath.exists()) return false
    var db: SQLiteDatabase? = null
    return try {
      db = SQLiteDatabase.openDatabase(
        dbPath.absolutePath,
        passphrase,
        null,
        SQLiteDatabase.OPEN_READONLY,
        null,
      )
      db.version
      true
    } catch (_: Exception) {
      false
    } finally {
      db?.close()
    }
  }

  fun rekey(
    dbPath: File,
    oldPassphrase: String,
    newPassphrase: String,
  ) {
    val db = SQLiteDatabase.openDatabase(
      dbPath.absolutePath,
      oldPassphrase,
      null,
      SQLiteDatabase.OPEN_READWRITE,
      null,
    )
    try {
      db.changePassword(newPassphrase)
    } finally {
      db.close()
    }
  }

  fun getDatabaseState(dbPath: File, defaultPassphrase: String): State {
    if (dbPath.exists()) {
      var db: SQLiteDatabase? = null

      try {
        db = SQLiteDatabase.openDatabase(
          dbPath.absolutePath,
          "",
          null,
          SQLiteDatabase.OPEN_READONLY,
          null,
        )
        db.version
        return State.UNENCRYPTED
      } catch (_: Exception) {

      } finally {
        db?.close()
      }

      try {
        db = SQLiteDatabase.openDatabase(
          dbPath.absolutePath,
          defaultPassphrase,
          null,
          SQLiteDatabase.OPEN_READONLY,
          null,
        )
        db.version
        return State.ENCRYPTED_DEFAULT
      } catch (_: Exception) {

      } finally {
        db?.close()
      }

      return State.ENCRYPTED
    }

    return State.DOES_NOT_EXIST
  }

  fun encryptTo(
    originalFile: File,
    targetFile: File,
    passphrase: String?
  ) {
    if (originalFile.exists()) {
      val originalDb = SQLiteDatabase.openDatabase(
        originalFile.absolutePath,
        "",
        null,
        SQLiteDatabase.OPEN_READWRITE,
        null,
      )
      val version = originalDb.version

      originalDb.close()

      val db = SQLiteDatabase.openOrCreateDatabase(
        targetFile.absolutePath,
        passphrase,
        null,
        null
      )

      //language=text
      val st = db.compileStatement("ATTACH DATABASE ? AS plaintext KEY ''")

      st.bindString(1, originalFile.absolutePath)
      st.execute()
      db.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
      db.rawExecSQL("DETACH DATABASE plaintext")
      db.version = version
      st.close()
      db.close()
    } else {
      throw FileNotFoundException(originalFile.absolutePath + " not found")
    }
  }
}