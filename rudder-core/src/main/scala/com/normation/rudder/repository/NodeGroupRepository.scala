/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.repository

import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.queries.Query
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.nodes._
import net.liftweb.common._
import com.normation.inventory.domain.NodeId
import com.normation.utils.Utils
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import scala.collection.immutable.SortedMap
import com.normation.rudder.domain.policies._

/**
 * Here is the ordering for a List[NodeGroupCategoryId]
 * MUST start by the root !
 */
object GroupCategoryRepositoryOrdering extends Ordering[List[NodeGroupCategoryId]] {
  type ID = NodeGroupCategoryId
  override def compare(x:List[ID],y:List[ID]) = {
    Utils.recTreeStringOrderingCompare(x.map( _.value ), y.map( _.value ))

  }
}

/**
 * Here is the ordering for a List[NodeGroupCategoryId]
 * MUST start by the root !
 */
object NodeGroupCategoryOrdering extends Ordering[List[NodeGroupCategoryId]] {
  type ID = NodeGroupCategoryId
  override def compare(x:List[ID],y:List[ID]) = {
    Utils.recTreeStringOrderingCompare(x.map( _.value ), y.map( _.value ))
  }
}


/**
 * A simple container for a category
 * and its direct children ActiveTechniques
 */
final case class CategoryAndNodeGroup(
    category: NodeGroupCategory
  , groups  : Set[NodeGroup]
) extends HashcodeCaching


final case class FullNodeGroupCategory(
    id           : NodeGroupCategoryId
  , name         : String
  , description  : String
  , subCategories: List[FullNodeGroupCategory]
  , targetInfos  : List[FullRuleTargetInfo]
  , isSystem     : Boolean = false
) extends Loggable with HashcodeCaching {

  def toNodeGroupCategory = NodeGroupCategory(
      id = id
    , name = name
    , description = description
    , children = subCategories.map( _.id )
    , items = targetInfos.map( _.toTargetInfo )
    , isSystem = isSystem
  )

  /**
   * Get the list of categories, starting by that one,
   * and with chlidren sorted with the given ordering.
   * So we get:
   * cat1
   *  - cat1.1
   *     - cat1.1.1
   *     - cat1.1.2
   *  - cat1.2
   *     - cat1.2.1
   *     etc.
   *
   * Some categories AND ALL THERE SUBCATEGORIES can be
   * exclude with the "exclude" predicat is true.
   */
  def getSortedCategories(
      ordering: (FullNodeGroupCategory, FullNodeGroupCategory) => Boolean
    , exclude: FullNodeGroupCategory => Boolean
  ) : List[(List[NodeGroupCategoryId], FullNodeGroupCategory)] = {

    if(exclude(this)){
      Nil
    } else {
      val subCats = for {
        directSubCat    <- subCategories.sortWith(ordering)
        (subId, subCat) <- directSubCat.getSortedCategories(ordering, exclude)
      } yield {
        (id :: subId, subCat)
      }

      (List(id) -> this) :: subCats
    }
  }

  val ownGroups = targetInfos.collect {
        case FullRuleTargetInfo(g:FullGroupTarget, _, _, _, _) => (g.nodeGroup.id, g)
      }.toMap

  val allGroups: Map[NodeGroupId, FullGroupTarget] = (
      ownGroups ++ subCategories.flatMap( _.allGroups )
  ).toMap

  val categoryByGroupId: Map[NodeGroupId, NodeGroupCategoryId] = (
      ownGroups.map { case (gid, _) => (gid, id)} ++ subCategories.flatMap( _.categoryByGroupId)
  ).toMap

  val allCategories: Map[NodeGroupCategoryId, FullNodeGroupCategory] = {
      subCategories.flatMap( _.allCategories ) :+ (id -> this)
  }.toMap

  val allTargets: Map[RuleTarget, FullRuleTargetInfo] = (
      targetInfos.map(t => (t.target.target, t)).toMap ++ subCategories.flatMap( _.allTargets)
  )


  /**
   * Return all node ids that match the set of target.
   */
  def getNodeIds(targets: Set[RuleTarget], allNodeInfos: Map[NodeId, NodeInfo]) : Set[NodeId] = {
    val allNodeIds = allNodeInfos.keySet
    (Set[NodeId]()/:targets) {
      case (nodes, t:NonGroupRuleTarget) =>
        t match {
          case AllTarget => return allNodeIds
          case AllTargetExceptPolicyServers => nodes ++ allNodeInfos.collect { case(k,n) if(!n.isPolicyServer) => n.id }
          case PolicyServerTarget(nodeId) => nodes + nodeId
          case AllServersWithRole => nodes ++ allNodeInfos.collect { case(k,n) if(n.serverRoles.size>0) => n.id } 
        }
      //here, if we don't find the group, we consider it's an error in the
      //target recording, but don't fail, just log it.
      case (nodes, GroupTarget(groupId)) =>
        val nodesForGroup = allGroups.get(groupId).map( _.nodeGroup.serverList) match {
          case None =>
            logger.error(s"Ignoring target for Group with ID='${groupId.value}' because that group is not present in group library")
            Set()
          case Some(ids) => ids
        }
        nodes ++ nodesForGroup

      case (nodes, TargetIntersection(targets)) =>
        val nodeSets = targets.map(t => getNodeIds(Set(t),allNodeInfos))
        // Compute the intersection of the sets of Nodes
        val intersection = (allNodeIds/: nodeSets) {
          case (currentIntersection, nodes) => currentIntersection.intersect(nodes)
        }
        nodes ++ intersection

      case (nodes, TargetUnion(targets)) =>
        val nodeSets = targets.map(t => getNodeIds(Set(t),allNodeInfos))
        // Compute the union of the sets of Nodes
        val union = (Set[NodeId]()/: nodeSets) {
          case (currentUnion, nodes) => currentUnion.union(nodes)
        }
        nodes ++ union

      case (nodes, TargetExclusion(included,excluded)) =>
        // Compute the included Nodes
        val includedNodes = getNodeIds(Set(included),allNodeInfos)
        // Compute the excluded Nodes
        val excludedNodes = getNodeIds(Set(excluded),allNodeInfos)
        // Remove excluded nodes from included nodes
        val result = includedNodes -- excludedNodes
        nodes ++ result


      case (nodes,target) =>
        logger.debug(s"Cannot find nodes from a Rule target, the target is : ${target}")
        nodes
    }
  }
}

trait RoNodeGroupRepository {

  /**
   * Get the full group tree with all information
   * for categories and groups.
   * Returns the objects sorted by name within
   */
  def getFullGroupLibrary(): Box[FullNodeGroupCategory]

  /**
   * Get a server group by its id
   * @param id
   * @return
   */
  def getNodeGroup(id: NodeGroupId) : Box[(NodeGroup,NodeGroupCategoryId)]


  /**
   * Fetch the parent category of the NodeGroup
   * Caution, its a lightweight version of the entry (no children nor item)
   * @param id
   * @return
   */
  def getParentGroupCategory(id: NodeGroupId): Box[NodeGroupCategory]

  /**
   * Get all node groups defined in that repository
   */
  def getAll() : Box[Seq[NodeGroup]]

  /**
   * Get all pairs of (category details, Set(node groups) )
   * in a map in which keys are the parent category of the groups.
   * The map is sorted by category:
   *
   *   "/"           -> [/_details, Set(G1, G2)]
   *   "/cat1"       -> [cat1_details, Set(G3)]
   *   "/cat1/cat11" -> [/cat1/cat11_details, Set(G4)]
   *   "/cat2"       -> [/cat2_details, Set(G5)]
   *   ...    *
   *
   */
  def getGroupsByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]]

  /**
   * Retrieve all groups that have at least one of the given
   * node ID in there member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAnyMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]]

  /**
   * Retrieve all groups that have ALL given node ID in their
   * member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAllMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]]


  /**
   * Root group category
   */
  def getRootCategory() : NodeGroupCategory

  /**
   * Get all pairs of (categoryid, category)
   * in a map in which keys are the parent category of the
   * the template. The map is sorted by categories:
   * SortedMap {
   *   "/"           -> [root]
   *   "/cat1"       -> [cat1_details]
   *   "/cat1/cat11" -> [/cat1/cat11]
   *   "/cat2"       -> [/cat2_details]
   *   ...
   */
  def getCategoryHierarchy : Box[SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]]


  /**
   * retrieve the hierarchy of group category/group containing the selected node
   * From a category id (should start from root) return Empty if no children nor items contains the targets, Full(category) otherwise, with both
   * target and children filtered.
   * Probably suboptimal
   */
  def findGroupHierarchy(categoryId : NodeGroupCategoryId, targets : Seq[RuleTarget])  : Box[NodeGroupCategory]


  /**
   * Return all categories
   * @return
   */
  def getAllGroupCategories(includeSystem: Boolean = false) : Box[List[NodeGroupCategory]]

  /**
   * Get a group category by its id
   * @param id
   * @return
   */
  def getGroupCategory(id: NodeGroupCategoryId) : Box[NodeGroupCategory]

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentGroupCategory(id:NodeGroupCategoryId) : Box[NodeGroupCategory]

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParents_NodeGroupCategory(id:NodeGroupCategoryId) : Box[List[NodeGroupCategory]]

  /**
   * Returns all non system categories + the root category
   * Caution, they are "lightweight" group categories (no children)
   */
  def getAllNonSystemCategories() : Box[Seq[NodeGroupCategory]]

}

trait WoNodeGroupRepository {
  //// write operations ////


  /**
   * Add a server group into the a parent category
   * Fails if the parent category does not exists
   * The id provided by the nodeGroup will  be used to save it inside the repository
   * return the newly created server group
   */
  def create(group: NodeGroup, into: NodeGroupCategoryId, modId: ModificationId, actor:EventActor, why: Option[String]): Box[AddNodeGroupDiff]


  /**
   * Update the given existing group
   * That method does nothing at the configuration level,
   * so you will have to manage rule deployment
   * if needed
   *
   * System group can not be updated with that method.
   */
  def update(group:NodeGroup, modId: ModificationId, actor:EventActor, whyDescription:Option[String]) : Box[Option[ModifyNodeGroupDiff]]

  /**
   * Update the given existing system group
   */
  def updateSystemGroup(group:NodeGroup, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyNodeGroupDiff]]

  /**
   * Move the given existing group to the new container.
   *
   * That *only* move the group, and don't modify anything
   * else. You will have to use udate/updateSystemGroup for
   * modification.
   *
   * That method does nothing at the configuration level,
   * so you will have to manage rule deployment
   * if needed
   */
  def move(group:NodeGroupId, containerId : NodeGroupCategoryId, modId: ModificationId, actor:EventActor, whyDescription:Option[String]) : Box[Option[ModifyNodeGroupDiff]]

  /**
   * Delete the given nodeGroup.
   * If no nodegroup has such id in the directory, return a success.
   * @param id
   * @return
   */
  def delete(id:NodeGroupId, modId: ModificationId, actor:EventActor, whyDescription:Option[String]) : Box[DeleteNodeGroupDiff]

  /**
   * Add that group category into the given parent category
   * Fails if the parent category does not exists or
   * if it already contains that category.
   *
   * return the new category.
   */
  def addGroupCategorytoCategory(
      that:NodeGroupCategory
    , into:NodeGroupCategoryId //parent category
    , modificationId: ModificationId
    , actor:EventActor, reason: Option[String]
  ) : Box[NodeGroupCategory]

  /**
   * Update an existing group category
   */
  def saveGroupCategory(category:NodeGroupCategory, modificationId: ModificationId, actor:EventActor, reason: Option[String]) : Box[NodeGroupCategory]

  /**
    * Update/move an existing group category
    */
   def saveGroupCategory(category: NodeGroupCategory, containerId : NodeGroupCategoryId, modificationId: ModificationId, actor:EventActor, reason: Option[String]): Box[NodeGroupCategory]

  /**
   * Delete the category with the given id.
   * If no category with such id exists, it is a success.
   * If checkEmtpy is set to true, the deletion may be done only if
   * the category is empty (else, category and children are deleted).
   * @param id
   * @param checkEmtpy
   * @return
   *  - Full(category id) for a success
   *  - Failure(with error message) iif an error happened.
   */
  def delete(id:NodeGroupCategoryId, modificationId: ModificationId, actor:EventActor, reason: Option[String], checkEmpty:Boolean = true) : Box[NodeGroupCategoryId]

}