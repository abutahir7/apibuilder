package db

import com.gilt.apidoc.models.User
import lib.{Constants, Role}
import anorm._
import play.api.db._
import play.api.Play.current
import play.api.libs.json._
import java.util.UUID
import scala.util.{Failure, Success, Try}

case class UserForm(email: String, password: String, name: Option[String] = None)

object UserForm {
  implicit val userFormReads = Json.reads[UserForm]
}

object UsersDao {

  private val BaseQuery = """
    select guid, email, name
      from users
     where deleted_at is null
  """

  private val InsertQuery = """
    insert into users
    (guid, email, name, created_by_guid, updated_by_guid)
    values
    ({guid}::uuid, {email}, {name}, {created_by_guid}::uuid, {updated_by_guid}::uuid)
  """

  private val UpdateQuery = """
  update users
     set email = {email},
         name = {name}
   where guid = {guid}
  """

  def update(updatingUser: User, user: User, form: UserForm) {
    DB.withConnection { implicit c =>
      SQL(UpdateQuery).on('guid -> user.guid,
                          'email -> form.email,
                          'name -> form.name,
                          'updated_by_guid -> updatingUser.guid).execute()
    }

    if (user.email.trim.toLowerCase != form.email.trim.toLowerCase) {
      EmailVerificationsDao.upsert(updatingUser, user, form.email)
    }

  }

  def create(form: UserForm): User = {
    val guid = UUID.randomUUID
    DB.withTransaction { implicit c =>
      SQL(InsertQuery).on('guid -> guid,
                          'email -> form.email.trim,
                          'name -> form.name.map(_.trim),
                          'created_by_guid -> Constants.DefaultUserGuid,
                          'updated_by_guid -> Constants.DefaultUserGuid).execute()

      UserPasswordsDao.doCreate(c, guid, guid, form.password)
    }

    val user = findByGuid(guid).getOrElse {
      sys.error("Failed to create user")
    }

    OrganizationsDao.findByEmailDomain(form.email).foreach { org =>
      MembershipRequestsDao.create(user, org, user, Role.Member)
    }

    EmailVerificationsDao.create(user, user, form.email)

    user
  }

  def findByToken(token: String): Option[User] = {
    findAll(token = Some(token)).headOption
  }

  def findByEmail(email: String): Option[User] = {
    findAll(email = Some(email)).headOption
  }

  def findByGuid(guid: String): Option[User] = {
    findAll(guid = Some(guid)).headOption
  }

  def findByGuid(guid: UUID): Option[User] = {
    findByGuid(guid.toString)
  }

  def findAll(guid: Option[String] = None,
              email: Option[String] = None,
              token: Option[String] = None): Seq[User] = {
    require(!guid.isEmpty || !email.isEmpty || !token.isEmpty, "Must have either a guid, email or token")

    val sql = Seq(
      Some(BaseQuery.trim),
      guid.map { v =>
        Try(UUID.fromString(v)) match {
          case Success(uuid) => "and users.guid = {guid}::uuid"
          case Failure(e) => e match {
            case e: IllegalArgumentException => "and false"
          }
        }
      },
      guid.map { v => "and users.guid = {guid}::uuid" },
      email.map { v => "and users.email = trim(lower({email}))" },
      token.map { v => "and users.guid = (select user_guid from tokens where token = {token} and deleted_at is null)"},
      Some("limit 1")
    ).flatten.mkString("\n   ")

    val bind = Seq[Option[NamedParameter]](
      guid.map('guid -> _),
      email.map('email -> _),
      token.map('token ->_)
    ).flatten

    DB.withConnection { implicit c =>
      SQL(sql).on(bind: _*)().toList.map { fromRow(_) }.toSeq
    }
  }

  private[db] def fromRow(
    row: anorm.Row,
    prefix: Option[String] = None
  ) = {
    val p = prefix.map( _ + "_").getOrElse("")
    User(
      guid = row[UUID](s"${p}guid"),
      email = row[String](s"${p}email"),
      name = row[Option[String]](s"${p}name")
    )
  }

}
