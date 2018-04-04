// Copyright (c) 2017 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dynamics
package cli

import dynamics.common._

import scala.scalajs.js
import js._
import annotation._
import JSConverters._
import js.Dynamic.{literal => jsobj}

import scala.concurrent._
import scala.concurrent.duration._
import fs2._
import cats._
import cats.data._
import cats.implicits._
import cats.effect._

import io.scalajs.npm.chalk._

import MonadlessIO._
import dynamics.http._
import dynamics.client._
import dynamics.client.implicits._
import dynamics.common.implicits._
import dynamics.http.implicits._
import client.common._

@js.native
trait EntityDefinition extends js.Object {
  val PrimaryNameAttribute: String = js.native
  val LogicalName: String = js.native
  val SchemaName: String = js.native
  val PrimaryIdAttribute: String = js.native
  val LogicalCollectionName: String = js.native
  val CollectionSchemaName: String = js.native
  val EntitySetName: String = js.native
  val IsLogical: Boolean = js.native
  val MetadataId: String = js.native
  val IsCustomEntity: Boolean = js.native
  val IsActivity: Boolean = js.native
  val IsActivityParty: Boolean = js.native
  val Description: String = js.native
  val ObjectTypeCode: Int = js.native
  val ColumnNumber: Int = js.native

  /** Used only for the Attribute pull. */
  val Attributes: js.UndefOr[js.Array[AttributeMetadata]] = js.native
}

@js.native
trait AttributeTypeCode extends js.Object {
  val code: Int = js.native
  val label: String = js.native
}

@js.native
trait AttributeMetadata extends js.Object {
  @JSName("@odata.type")
  val odatatype: String = js.native
  val SchemaName: String = js.native
  val LogicalName: String = js.native
  val AttributeType: String = js.native
  val IsValidForRead: Boolean  = js.native// can be read in a retrieve
  val IsPrimaryId: Boolean = js.native
  val IsLogical: Boolean  = js.native// whether stored in a different table
  val AttributeOf: Option[String] = js.native
  val ColumnNumber: Int = js.native
  val MetadataId: String = js.native
  val EntityLogicalName: String = js.native
  // ...
}

case class Property(name: String, edmType: String)

/**
 * Option metadata complex type.
 * @see https://docs.microsoft.com/en-us/dynamics365/customer-engagement/web-api/optionmetadata?view=dynamics-ce-odata-9
 * 
*/
@js.native
trait OptionSetItem extends js.Object {
  val Value: Int = js.native
  val Label: LocalizedInfo = js.native
  val Description: LocalizedInfo = js.native
  val IsManaged: Boolean = js.native
  val Color: js.UndefOr[String] = js.native
}

/**  
 * OptionSetMetadata. This is the object returned from the OptionSet or
 * GlobalOptionSet attribute property.
 * @see https://docs.microsoft.com/en-us/dynamics365/customer-engagement/web-api/optionsetmetadata?view=dynamics-ce-odata-9
 */
@js.native
trait OptionSetResponse extends js.Object {
  val Name: String = js.native
  val IsGlobal: Boolean = js.native
  val HasChanged: Boolean = js.native
  val IsCustomOptionSet: Boolean = js.native
  val Description: LocalizedInfo = js.native
  val DisplayName: LocalizedInfo = js.native
  val MetadataId: String = js.native
  val ExternalTypeName: String = js.native
  val OptionSetType: String = js.native
  val Options: js.Array[OptionSetItem] = js.native  
}

/** The actual trait that we should use. */
@js.native
trait OptionSetMetadata extends OptionSetResponse

/** 
 * When asking for both local or global options set. Either one of these
 * could be null as well according to the documentation.
 * 
 * @see https://msdn.microsoft.com/en-us/library/mt788314.aspx
 */
@js.native
trait LocalOrGlobalOptionSetsResponse extends js.Object {
  val OptionSet: UndefOr[OptionSetResponse]       = js.native
  val GlobalOptionSet: UndefOr[OptionSetResponse] = js.native
}

case class OptionValue(label: String, value: Int)

@js.native
trait ConnectionRole extends js.Object {
  val connectionroleid: String = js.native
  val name: String = js.native
  /** FK to ConnectionRoleCategory */
  val category: Int = js.native
  /** Name of ConnectionRoleCategory */
  @JSName("category@OData.Community.Display.V1.FormattedValue")
  val category_fv: String = js.native
  val description: String = js.native
  val statecode: Int = js.native
  val statuscode: Int = js.native
  @JSName("connectionroleassociation_association@odata.nextLink")
  val reciprocals_link: String = js.native
}

/**
 * Simple metadata cache for CRM metadata.
 */
class MetadataCache(protected val context: DynamicsContext)
  (implicit F: MonadError[IO, Throwable]) {

  import context._
  import dynamics.common.syntax.all._
  import LocalizedHelpers._

  protected val cache = new NodeCache()

  def entityDefinitions(): IO[Seq[EntityDefinition]] = {
    val elist = cache.get[Seq[EntityDefinition]]("entityDefs")
    elist.fold{
      val q = QuerySpec(filter = Some("IsPrivate eq false"))
      dynclient.getList[EntityDefinition](q.url("EntityDefinitions"))
        .map { eds =>
          cache.set("entityDefs", eds)
          eds.foreach(ed => cache.set(s"Entity:${ed.LogicalName}", ed))
          eds.foreach(ed => cache.set(s"EntitySet:${ed.LogicalCollectionName}", ed))
          eds
        }
      // be more generous about error handling here?
    }{
      items => F.pure(items)
    }
  }

  def entityByName(entityName: String): OptionT[IO, EntityDefinition] =
    OptionT(entityDefinitions().map { _ =>
      cache.get[EntityDefinition](s"Entity:$entityName").toOption
    })

  def entityBySetName(entitySetName: String): OptionT[IO, EntityDefinition] =
    OptionT(entityDefinitions().map { _ =>
      cache.get[EntityDefinition](s"EntitySet:$entitySetName").toOption
    })

  def entitySetName(entityName: String): OptionT[IO, String] =
    entityByName(entityName).map(ed => ed.LogicalCollectionName)

  def entityName(entitySetName: String): OptionT[IO, String] =
    entityBySetName(entitySetName).map(ed => ed.LogicalName)

  def objectTypeCode(entityName: String): OptionT[IO, Int] =
    entityByName(entityName).map(ed => ed.ObjectTypeCode)

  /** @deprecated Use [[entitySetName]] */
  def entityDescription(entitySet: String): OptionT[IO, EntityDefinition] =
    entityBySetName(entitySet)

  def pk(entityName: String): OptionT[IO, String] =
    entityByName(entityName).map(_.PrimaryIdAttribute)

  def baseAttributes(entityName: String): IO[Seq[AttributeMetadata]] = {
    val alist = cache.get[Seq[AttributeMetadata]](s"Attributes:$entityName")
    alist.fold {
      // not found, so get it, create a OptionT[IO, Seq[]]
      entityByName(entityName).flatMapF { ed =>
        val q = QuerySpec(
          select=Seq("LogicalName"),
          filter=Some(s"""MetadataId eq ${ed.MetadataId}"""),
          expand=Seq(Expand("Attributes"))
        )
        // only get the attributes, flip to IO[Option[Seq]]
        dynclient.getList[EntityDefinition](q.url("EntityDefinitions"))
          .map{ eds =>
            //js.Dynamic.global.console.log("returned attributes", eds(0).Attributes)
            if(eds.length > 0) {
              val attributes = eds(0).Attributes.map(_.toSeq)
              attributes.foreach{ attrs =>
                cache.set(s"Attributes:$entityName", attrs)
                attrs.foreach(a => cache.set(s"Entity:$entityName:Attribute:${a.LogicalName}", a))
              }
              attributes.toOption
            } else Option.empty
          }
        // handle error on getList a bit better than this?
      }
        .getOrElse(Seq.empty)
    }{
      // found them
      items => F.pure(items)
    }
  }

  def attributeMetadataId(entitySetName: String, attribute: String): OptionT[IO, String] = {
    entityName(entitySetName)
      .flatMapF { ename =>
        baseAttributes(ename).map { _ =>
          val attr = cache.get[AttributeMetadata](s"Entity:$ename:Attribute:$attribute")
          attr.map(_.MetadataId).toOption
        }
      }
  }

  private def govKey(e: String, a: String) = s"OptionValues:$e:$a"

  /** 
   * Obtain option values give an entity and attribute combination.
   * @todo Should we signal an erro if either entiytSet or attribute is not found?
   */
  def optionValues(entitySet: String, attribute: String): IO[Seq[OptionValue]] = {
    val k = govKey(entitySet, attribute)
    val ov = cache.get[Seq[OptionValue]](k)
    ov.fold {
      // did not find it
      lift {
        implicit val decoder = jsObjectDecoder[LocalOrGlobalOptionSetsResponse](context.e)
        val eid = unlift(entityBySetName(entitySet).map(_.MetadataId).value)
        val aid = unlift(attributeMetadataId(entitySet, attribute).value)
        //println(s"e-a key: $eid, $aid")
          (eid, aid) match {
          case (Some(e), Some(a)) =>
            val q = QuerySpec(
              properties =
                Seq(NavProperty("Attributes", aid, Some("Microsoft.Dynamics.CRM.PicklistAttributeMetadata"))),
              select = Seq("LogicalName"),
              expand=Seq(
                Expand("OptionSet", select=Seq("Options")),
                Expand("GlobalOptionSet", select=Seq("Options")))
            )
            val url = q.url("EntityDefinitions", eid)
            unlift(dynclient.getOne[LocalOrGlobalOptionSetsResponse](url).map { resp =>
              // Chose global or local, either could be null, values and content
              // should be identical if both are present.
              val options =
                (resp.GlobalOptionSet.toNonNullOption.map(_.Options) orElse
                  resp.OptionSet.toNonNullOption.map(_.Options)).getOrElse(js.Array())

              val result: Seq[OptionValue] = options.map { oitem =>
                LocalizedHelpers.findByLCID(context.LCID, oitem.Label)
                  .map(locLabel => OptionValue(locLabel.Label, oitem.Value))
                  .getOrElse(throw new IllegalArgumentException(
                    s"Unable to find localized label for $entitySet.$attribute OptionSet."))
              }.toSeq
              cache.set(k, result) // into cache
              result
            })
          case _ =>
            Seq.empty
        }
      }}
    {
      items => F.pure(items)
    }
  }

  protected def optionSetMetadataToOptionValues(items: js.Array[OptionSetItem]): Seq[OptionValue] =
    items.map { oitem =>
      LocalizedHelpers.findByLCID(context.LCID, oitem.Label)
        .map(locLabel => OptionValue(locLabel.Label, oitem.Value))
        .getOrElse(throw new IllegalArgumentException(
          s"Unable to find localized label for OptionSet."))
    }.toSeq

  /**
   * Given an entity set name and an attribute name, return the attribute type.
   */
  def attributeType(ename: String, aname: String): OptionT[IO, String] = {
    OptionT(baseAttributes(ename).map { _ =>
      val attr = cache.get[AttributeMetadata](s"Entity:$ename:Attribute:$aname")
      attr.fold{ Option.empty[String] }
      { attr => Some(attr.AttributeType) }
    })
  }

  def globalOptionSets(): IO[Seq[OptionSetMetadata]] = {
    val osets = cache.get[Seq[OptionSetMetadata]]("globalOptionSets")
    osets.fold {
      dynclient.getList[OptionSetMetadata]("/GlobalOptionSetDefinitions")
        .map { oms =>
          cache.set("globalOptionSets", oms)
          oms.foreach(om => cache.set(s"GlobalOptionSet:${om.Name}", om))
          oms.toSeq
        }
    }{
      items => F.pure(items)
    }
  }

  def globalOptionSet(name: String): IO[Seq[OptionValue]] = {
    globalOptionSets().map { _ =>
      val osm = cache.get[OptionSetMetadata](s"GlobalOptionSet:$name")
      osm.fold{ Seq.empty[OptionValue] }
      { o => optionSetMetadataToOptionValues(o.Options)}
    }
  }

  def connectionRoleCategories() =
    globalOptionSet("connectionrole_category")

  def connectionRoles(): IO[Seq[ConnectionRole]] = {
    val croles = cache.get[Seq[ConnectionRole]]("connectionRoles")
    croles.fold{
      // not found
      val q = QuerySpec(
        filter = Some("statecode eq 0"),
        expand = Seq(Expand("connectionroleassociation_association"))
      )
      dynclient.getList[ConnectionRole](q.url("connectionroles"))
        .map { items =>
          cache.set("connectionRoles", items)
          items.foreach(cr => cache.set(s"ConnectionRole:${cr.name}", cr))
          items.toSeq
        }
    }{
      items => F.pure(items)
    }
  }

  def connectionRolesForCategory(cname: String, ignoreCase: Boolean = false): IO[Seq[ConnectionRole]] = {
    connectionRoles().map(_.filter{ cr =>
      if(ignoreCase) cr.category_fv.toLowerCase == cname.toLowerCase
      else cr.category_fv == cname
    })
  }

}

@js.native
@JSImport("shorthash", JSImport.Namespace)
object ShortHash extends js.Object {
  def unique(in: String): String = js.native
}

/** Cache the metadata string returned from CRM. CSDL is cached across runs. */
case class CSDLFileCache(name: String, context: DynamicsContext, ignore: Boolean = false, location: String = ".")
    extends FileCache(Utils.pathjoin(location, ShortHash.unique(name) + ".csdl.cache"), ignore) {

  protected def getContent() = (new MetadataActions(context)).getCSDL()

}