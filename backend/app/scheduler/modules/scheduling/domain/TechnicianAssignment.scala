package scheduler.modules.scheduling.domain

import scheduler.modules.identity.Role

/**
 * Who may put which technician on a scheduled service job:
 *
 *  - a technician may assign only themselves (take the job);
 *  - a service advisor may assign a technician, but never themselves or another advisor
 *    (the target technician profile must belong to a technician account, or to none).
 *
 * The workshop controller and the admin keep their wider "move a job" tool (reassign).
 */
object TechnicianAssignment:

  enum Refusal:
    case ROLE_NOT_ALLOWED, ONLY_SELF, NOT_LINKED_TO_TECHNICIAN, CANNOT_ASSIGN_SELF, NOT_A_TECHNICIAN

  /** The account a technician profile is signed in with, when it has one. */
  final case class Account(userId: String, role: Role)

  /**
   * @param actorTechnicianId the caller's own technician profile, if they have one
   * @param targetAccount     the account of the technician profile being assigned, if any
   */
  def check(
      actorRole: Role,
      actorUserId: String,
      actorTechnicianId: Option[String],
      targetTechnicianId: String,
      targetAccount: Option[Account]
  ): Option[Refusal] =
    actorRole match
      case Role.TECHNICIAN =>
        actorTechnicianId match
          case None                                  => Some(Refusal.NOT_LINKED_TO_TECHNICIAN)
          case Some(own) if own != targetTechnicianId => Some(Refusal.ONLY_SELF)
          case Some(_)                               => None
      case Role.SERVICE_ADVISOR =>
        targetAccount match
          case Some(a) if a.userId == actorUserId  => Some(Refusal.CANNOT_ASSIGN_SELF)
          case Some(a) if a.role != Role.TECHNICIAN => Some(Refusal.NOT_A_TECHNICIAN)
          case _                                    => None
      case _ => Some(Refusal.ROLE_NOT_ALLOWED)
