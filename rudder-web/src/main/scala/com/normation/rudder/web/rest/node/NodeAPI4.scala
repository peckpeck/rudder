/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.rest.node

import com.normation.inventory.domain.NodeId
import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.common._
import net.liftweb.json.JsonDSL._


class NodeAPI4 (
    apiV2         : NodeAPI2
  , apiV4         : NodeApiService4
  , restExtractor : RestExtractorService
) extends RestHelper with NodeAPI with Loggable{


  val v4Dispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

   case Get(id :: Nil, req) if id != "pending" => {
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) =>
          apiV4.nodeDetailsGeneric(NodeId(id),level,req)
        case eb:EmptyBox =>
          val failMsg = eb ?~ "node detail level not correctly sent"
          toJsonError(None, failMsg.msg)("nodeDetail",restExtractor.extractPrettify(req.params))
      }
    }
  }

  // Node API Version 4 fallback to Node api2 if request is not handled in V4
  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = v4Dispatch orElse apiV2.requestDispatch

}
