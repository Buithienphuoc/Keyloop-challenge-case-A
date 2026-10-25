package scheduler.modules.scheduling.domain

import scheduler.modules.identity.Role
import AppointmentStatus.*

/**
 * Appointment lifecycle.
 *
 *   CONFIRMED ──start──▶ IN_PROGRESS ──finish──▶ COMPLETED
 *       │
 *       ├──cancel──▶ CANCELLED   (only before work starts)
 *       └──no-show─▶ NO_SHOW     (only once the start time has passed)
 */
object Lifecycle:
  private val Transitions: Map[AppointmentStatus, Set[AppointmentStatus]] = Map(
    CONFIRMED -> Set(IN_PROGRESS, CANCELLED, NO_SHOW),
    IN_PROGRESS -> Set(COMPLETED),
    COMPLETED -> Set.empty,
    CANCELLED -> Set.empty,
    NO_SHOW -> Set.empty
  )

  def canTransition(from: AppointmentStatus, to: AppointmentStatus): Boolean = Transitions(from).contains(to)

  /** Which roles may move an appointment into which status (ownership is checked separately). */
  private val AllowedByRole: Map[Role, Set[AppointmentStatus]] = Map(
    Role.CUSTOMER -> Set(CANCELLED),
    Role.SERVICE_ADVISOR -> Set(IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW),
    Role.ADMIN -> Set(IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW),
    Role.TECHNICIAN -> Set(IN_PROGRESS, COMPLETED),
    Role.SYSTEM_MANAGER -> Set.empty
  )

  def roleMayMoveTo(role: Role, to: AppointmentStatus): Boolean = AllowedByRole(role).contains(to)

  enum TransitionError:
    case INVALID_TRANSITION, ROLE_NOT_ALLOWED, ALREADY_STARTED, NOT_STARTED_YET

  /** Time-dependent rules on top of the state machine. */
  def checkTransition(from: AppointmentStatus, to: AppointmentStatus, role: Role, startMs: Long, nowMs: Long): Option[TransitionError] =
    import TransitionError.*
    if !canTransition(from, to) then Some(INVALID_TRANSITION)
    else if !roleMayMoveTo(role, to) then Some(ROLE_NOT_ALLOWED)
    else if to == CANCELLED && role == Role.CUSTOMER && nowMs >= startMs then Some(ALREADY_STARTED)
    else if to == NO_SHOW && nowMs < startMs then Some(NOT_STARTED_YET)
    else None
