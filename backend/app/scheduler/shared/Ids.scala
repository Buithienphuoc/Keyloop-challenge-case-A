package scheduler.shared

import java.security.SecureRandom

object Ids:
  // Crockford-style alphabet without look-alike characters (no 0/O, 1/I/L).
  private val Alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
  private val random = SecureRandom()

  /** Short human-friendly reference, e.g. "SV-7K3Q9M", read out over the phone at the service desk. */
  def humanReference(prefix: String, length: Int = 6): String =
    val chars = Array.fill(length)(Alphabet.charAt(random.nextInt(Alphabet.length)))
    s"$prefix-${String(chars)}"
