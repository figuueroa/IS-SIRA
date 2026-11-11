package com.example.sira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

data class ExpedienteMedico(
    val matriculaAlumno: String,
    val tipoSangre: String,
    val alergias: String,
    val padecimientos: String,
    val medicamentos: String,
    val contactoEmergencia: String,
    val telefonoEmergencia: String
)

@Entity(tableName = "expedientes_medicos")
data class ExpedienteMedicoEntity(
    @PrimaryKey val matriculaAlumno: String,
    val tipoSangre: String,
    val alergias: String,
    val padecimientos: String,
    val medicamentos: String,
    val contactoEmergencia: String,
    val telefonoEmergencia: String
)

@Dao
interface ExpedienteMedicoDao {
    @Query("SELECT * FROM expedientes_medicos WHERE matriculaAlumno = :matricula LIMIT 1")
    suspend fun obtener(matricula: String): ExpedienteMedicoEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(expediente: ExpedienteMedicoEntity): Long
}

@Database(entities = [ExpedienteMedicoEntity::class], version = 1, exportSchema = false)
abstract class SiraDatabase : RoomDatabase() {
    abstract fun expedientesDao(): ExpedienteMedicoDao
}

interface ExpedienteMedicoRepository {
    suspend fun obtener(matricula: String): ExpedienteMedico?
    /** Devuelve false si ya existe un expediente para la matrícula. */
    suspend fun insertar(expediente: ExpedienteMedico): Boolean
}

private class RoomExpedienteMedicoRepository(private val dao: ExpedienteMedicoDao) : ExpedienteMedicoRepository {
    override suspend fun obtener(matricula: String): ExpedienteMedico? = dao.obtener(matricula)?.toModelo()

    override suspend fun insertar(expediente: ExpedienteMedico): Boolean =
        dao.insertar(expediente.toEntity()) != -1L
}

object ServiciosExpedientes {
    @Volatile
    private var instancia: ExpedienteMedicoRepository? = null

    val repositorio: ExpedienteMedicoRepository
        get() = checkNotNull(instancia) { "ServiciosExpedientes no fue inicializado en Application" }

    fun inicializar(context: Context) {
        if (instancia != null) return
        synchronized(this) {
            if (instancia == null) {
                System.loadLibrary("sqlcipher")
                val appContext = context.applicationContext
                val factory = SupportOpenHelperFactory(ClaveBaseDatos.obtener(appContext))
                val db = Room.databaseBuilder(appContext, SiraDatabase::class.java, "sira_medica.db")
                    .openHelperFactory(factory)
                    .build()
                instancia = RoomExpedienteMedicoRepository(db.expedientesDao())
            }
        }
    }
}

/** Cifra la clave aleatoria de SQLCipher con una llave no exportable del Android Keystore. */
private object ClaveBaseDatos {
    private const val ALIAS = "sira_room_key_wrap_v1"
    private const val PREFS = "sira_db_key"
    private const val IV = "iv"
    private const val CIPHERTEXT = "ciphertext"
    private const val TAG_BITS = 128

    @Synchronized
    fun obtener(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivBase64 = prefs.getString(IV, null)
        val cifradoBase64 = prefs.getString(CIPHERTEXT, null)
        if ((ivBase64 == null) != (cifradoBase64 == null)) {
            error("La clave cifrada de la base de datos está incompleta")
        }

        if (ivBase64 != null && cifradoBase64 != null) {
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cifrado = Base64.decode(cifradoBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, obtenerLlaveAndroid(), GCMParameterSpec(TAG_BITS, iv))
            return cipher.doFinal(cifrado)
        }

        val frase = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, obtenerLlaveAndroid())
        val cifrado = cipher.doFinal(frase)
        val guardado = prefs.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(cifrado, Base64.NO_WRAP))
            .commit()
        check(guardado) { "No se pudo proteger la clave de la base de datos" }
        return frase
    }

    private fun obtenerLlaveAndroid(): java.security.Key {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? java.security.Key)?.let { return it }

        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generador.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generador.generateKey()
    }
}

private fun ExpedienteMedicoEntity.toModelo() = ExpedienteMedico(
    matriculaAlumno, tipoSangre, alergias, padecimientos, medicamentos, contactoEmergencia, telefonoEmergencia
)

private fun ExpedienteMedico.toEntity() = ExpedienteMedicoEntity(
    matriculaAlumno, tipoSangre, alergias, padecimientos, medicamentos, contactoEmergencia, telefonoEmergencia
)
