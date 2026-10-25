package scheduler.modules.identity

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, HexFormat}
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing with PBKDF2-HMAC-SHA512 (in the JDK, no native library), a per-user random
 * salt and 210,000 iterations (the OWASP recommendation for this algorithm).
 * Stored as "pbkdf2-sha512$<iterations>$<salt>$<hash>" (base64url), so the cost can be raised
 * later without breaking existing hashes.
 */
object Passwords:
  private val Scheme = "pbkdf2-sha512"
  private val Iterations = 210_000
  private val KeyBits = 512
  private val random = SecureRandom()
  private val b64 = Base64.getUrlEncoder.withoutPadding()
  private val unb64 = Base64.getUrlDecoder

  def hash(password: String): String =
    val salt = Array.ofDim[Byte](16)
    random.nextBytes(salt)
    s"$Scheme$$$Iterations$$${b64.encodeToString(salt)}$$${b64.encodeToString(derive(password, salt, Iterations))}"

  def verify(password: String, stored: String): Boolean = stored.split('$') match
    case Array(Scheme, iterations, salt, expected) if iterations.toIntOption.isDefined =>
      val actual = derive(password, unb64.decode(salt), iterations.toInt)
      MessageDigest.isEqual(actual, unb64.decode(expected)) // constant time
    case _ => false

  private def derive(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = PBEKeySpec(password.toCharArray, salt, iterations, KeyBits)
    try SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded
    finally spec.clearPassword()

  /** Opaque session token handed to the client; only its SHA-256 is stored, so a DB leak exposes no live sessions. */
  def newSessionToken(): (String, String) =
    val bytes = Array.ofDim[Byte](32)
    random.nextBytes(bytes)
    val token = b64.encodeToString(bytes)
    (token, hashToken(token))

  def hashToken(token: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)))

  /** A hash of a random password, so a login for an unknown email takes as long as a real one. */
  lazy val dummyHash: String = hash(java.util.UUID.randomUUID().toString)
