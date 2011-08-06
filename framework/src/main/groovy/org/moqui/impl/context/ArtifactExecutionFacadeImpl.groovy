/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.context

import java.sql.Timestamp

import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactExecutionFacade
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.Cache
import org.moqui.entity.EntityList
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.impl.entity.EntityDefinition

public class ArtifactExecutionFacadeImpl implements ArtifactExecutionFacade {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ArtifactExecutionFacadeImpl.class)

    // NOTE: these need to be in a Map instead of the DB because Enumeration records may not yet be loaded
    protected final static Map<String, String> artifactTypeDescriptionMap = [AT_XML_SCREEN:"XML Screen",
            AT_XML_SCREEN_TRANS:"XML Screen Transition", AT_SERVICE:"Service", AT_ENTITY:"Entity"]
    protected final static Map<String, String> artifactActionDescriptionMap = [AUTHZA_VIEW:"View",
            AUTHZA_CREATE:"Create", AUTHZA_UPDATE:"Update", AUTHZA_DELETE:"Delete", AUTHZA_ALL:"All"]

    protected ExecutionContextImpl eci
    protected Deque<ArtifactExecutionInfoImpl> artifactExecutionInfoStack = new LinkedList<ArtifactExecutionInfoImpl>()
    protected List<ArtifactExecutionInfoImpl> artifactExecutionInfoHistory = new LinkedList<ArtifactExecutionInfoImpl>()

    // NOTE: there is no code to clean out old entries in tarpitHitCache, using the cache idle expire time for that
    protected Cache tarpitHitCache
    protected Map<String, Boolean> artifactTypeAuthzEnabled = new HashMap()
    protected Map<String, Boolean> artifactTypeTarpitEnabled = new HashMap()

    protected boolean disableAuthz = false

    ArtifactExecutionFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
        this.tarpitHitCache = eci.cache.getCache("artifact.tarpit.hits")
    }

    boolean isAuthzEnabled(String artifactTypeEnumId) {
        Boolean en = artifactTypeAuthzEnabled.get(artifactTypeEnumId)
        if (en == null) {
            Node aeNode = (Node) eci.ecfi.confXmlRoot."artifact-execution-facade"[0]."artifact-execution"
                    .find({ it."@type" == artifactTypeEnumId })
            en = aeNode != null ? !(aeNode."@authz-enabled" == "false") : true
            artifactTypeAuthzEnabled.put(artifactTypeEnumId, en)
        }
        return en
    }
    boolean isTarpitEnabled(String artifactTypeEnumId) {
        Boolean en = artifactTypeTarpitEnabled.get(artifactTypeEnumId)
        if (en == null) {
            Node aeNode = (Node) eci.ecfi.confXmlRoot."artifact-execution-facade"[0]."artifact-execution"
                    .find({ it."@type" == artifactTypeEnumId })
            en = aeNode != null ? !(aeNode."@tarpit-enabled" == "false") : true
            artifactTypeTarpitEnabled.put(artifactTypeEnumId, en)
        }
        return en
    }

    /** @see org.moqui.context.ArtifactExecutionFacade#peek() */
    ArtifactExecutionInfo peek() { return this.artifactExecutionInfoStack.peekFirst() }

    /** @see org.moqui.context.ArtifactExecutionFacade#pop() */
    ArtifactExecutionInfo pop() {
        if (this.artifactExecutionInfoStack.size() > 0) {
            return this.artifactExecutionInfoStack.removeFirst()
        } else {
            logger.warn("Tried to pop from an empty ArtifactExecutionInfo stack", new Exception("Bad pop location"))
            return null
        }
    }

    /** @see org.moqui.context.ArtifactExecutionFacade#push(ArtifactExecutionInfo, boolean) */
    void push(ArtifactExecutionInfo aei, boolean requiresAuthz) {
        ArtifactExecutionInfoImpl aeii = (ArtifactExecutionInfoImpl) aei
        // do permission check for this new aei that current user is trying to access
        String username = eci.getUser().getUsername()

        ArtifactExecutionInfoImpl lastAeii = artifactExecutionInfoStack.peekFirst()

        // always do this regardless of the authz checks, etc; keep a history of artifacts run
        artifactExecutionInfoHistory.add(aeii)

        if (!isPermitted(username, aeii, lastAeii, requiresAuthz, eci.getUser().getNowTimestamp()))
            throw new ArtifactAuthorizationException("User [${username}] is not authorized for ${artifactActionDescriptionMap.get(aeii.actionEnumId)} on ${artifactTypeDescriptionMap.get(aeii.typeEnumId)?:aeii.typeEnumId} [${aeii.name}]")

        // NOTE: if needed the isPermitted method will set additional info in aeii
        this.artifactExecutionInfoStack.addFirst(aeii)
    }

    /** @see org.moqui.context.ArtifactExecutionFacade#getStack() */
    Deque<ArtifactExecutionInfo> getStack() { return this.artifactExecutionInfoStack }

    /** @see org.moqui.context.ArtifactExecutionFacade#getHistory() */
    List<ArtifactExecutionInfo> getHistory() { return this.artifactExecutionInfoHistory }

    boolean disableAuthz() { boolean alreadyDisabled = this.disableAuthz; this.disableAuthz = true; return alreadyDisabled }
    void enableAuthz() { this.disableAuthz = false }

    /** Checks to see if username is permitted to access given resource.
     *
     * @param username
     * @param resourceAccess Formatted as: "${typeEnumId}:${actionEnumId}:${name}"
     * @param nowTimestamp
     * @param eci
     * @return
     */
    static boolean isPermitted(String username, String resourceAccess, Timestamp nowTimestamp, ExecutionContextImpl eci) {
        int firstColon = resourceAccess.indexOf(":")
        int secondColon = resourceAccess.indexOf(":", firstColon+1)
        if (firstColon == -1 || secondColon == -1) throw new ArtifactAuthorizationException("Resource access string does not have two colons (':'), must be formatted like: \"\${typeEnumId}:\${actionEnumId}:\${name}\"")

        String typeEnumId = resourceAccess.substring(0, firstColon)
        String actionEnumId = resourceAccess.substring(firstColon+1, secondColon)
        String name = resourceAccess.substring(secondColon+1)

        return eci.artifactExecutionFacade.isPermitted(username,
                new ArtifactExecutionInfoImpl(name, typeEnumId, actionEnumId), null, true, eci.user.nowTimestamp)
    }

    boolean isPermitted(String userId, ArtifactExecutionInfoImpl aeii, ArtifactExecutionInfoImpl lastAeii,
                        boolean requiresAuthz, Timestamp nowTimestamp) {

        // never do this for entities when disableAuthz, as we might use any below and would cause infinite recursion
        if (this.disableAuthz && aeii.getTypeEnumId() == "AT_ENTITY") {
            if (lastAeii != null && lastAeii.authorizationInheritable) aeii.copyAuthorizedInfo(lastAeii)
            return true
        }

        // this will be set inside the disableAuthz block
        Set<String> userGroupIdSet

        boolean alreadyDisabled = disableAuthz()
        try {
            // see if there is a UserAccount for the username, and if so get its userId as a more permanent identifier
            EntityValue ua = eci.getUser().getUserAccount()
            if (ua) userId = ua.userId
            userGroupIdSet = eci.getUser().getUserGroupIdSet()

            if (isTarpitEnabled(aeii.typeEnumId)) {
                // record and check velocity limit (tarpit)
                boolean recordHitTime = false
                long lockForSeconds = 0
                long checkTime = System.currentTimeMillis()
                String tarpitKey = userId + "@" + aeii.typeEnumId + ":" + aeii.name
                List<Long> hitTimeList = (List<Long>) tarpitHitCache.get(tarpitKey)
                EntityList artifactTarpitList = null
                // only check screens if they are the final screen in the chain (the target screen)
                if (aeii.typeEnumId != "AT_XML_SCREEN" || requiresAuthz) {
                    artifactTarpitList = eci.entity.makeFind("ArtifactTarpitCheckView")
                            .condition("userGroupId", ComparisonOperator.IN, userGroupIdSet).useCache(true).list()
                            .filterByAnd([artifactTypeEnumId:aeii.typeEnumId])
                }
                // if (aeii.typeEnumId == "AT_XML_SCREEN") logger.warn("TOREMOVE about to check tarpit [${tarpitKey}], userGroupIdSet=${userGroupIdSet}, artifactTarpitList=${artifactTarpitList}")
                for (EntityValue artifactTarpit in artifactTarpitList) {
                    if ((artifactTarpit.nameIsPattern && aeii.name.matches((String) artifactTarpit.artifactName)) ||
                            aeii.name == artifactTarpit.artifactName) {
                        recordHitTime = true
                        long maxHitsDuration = artifactTarpit.maxHitsDuration as Long
                        // count hits in this duration; start with 1 to count the current hit
                        long hitsInDuration = 1
                        for (Long hitTime in hitTimeList) if ((hitTime - checkTime) < maxHitsDuration) hitsInDuration++
                        // logger.warn("TOREMOVE artifact [${tarpitKey}], now has ${hitsInDuration} hits in ${maxHitsDuration} seconds")
                        if (hitsInDuration > artifactTarpit.maxHitsCount && artifactTarpit.tarpitDuration > lockForSeconds) {
                            lockForSeconds = artifactTarpit.tarpitDuration as Long
                            // logger.warn("TOREMOVE artifact [${tarpitKey}], exceeded ${artifactTarpit.maxHitsCount} in ${maxHitsDuration} seconds, locking for ${lockForSeconds} seconds")
                        }
                    }
                }
                if (recordHitTime) {
                    if (hitTimeList == null) { hitTimeList = new LinkedList<Long>(); tarpitHitCache.put(tarpitKey, hitTimeList) }
                    hitTimeList.add(System.currentTimeMillis())
                    // logger.warn("TOREMOVE recorded hit time for [${tarpitKey}], now has ${hitTimeList.size()} hits")

                    // check the ArtifactTarpitLock for the current artifact attempt before seeing if there is a new lock to create
                    // NOTE: this is NOT cached because it has an argument with nowTimestamp making a cached value of limited used
                    // NOTE: this only runs if we are recording a hit time for an artifact, so no performance impact otherwise
                    EntityList tarpitLockList = eci.entity.makeFind("ArtifactTarpitLock")
                            .condition([userId:userId, artifactName:aeii.name, artifactTypeEnumId:aeii.typeEnumId])
                            .condition("releaseDateTime", ComparisonOperator.GREATER_THAN, eci.user.nowTimestamp).list()
                    if (tarpitLockList) {
                        throw new ArtifactAuthorizationException("User [${userId}] has accessed ${artifactTypeDescriptionMap.get(aeii.typeEnumId)?:aeii.typeEnumId} [${aeii.name}] too many times and may not again until ${tarpitLockList.first.releaseDateTime}")
                    }
                }
                // record the tarpit lock
                if (lockForSeconds > 0) {
                    eci.service.sync().name("create", "ArtifactTarpitLock").parameters((Map<String, Object>) [userId:userId,
                            artifactName:aeii.name, artifactTypeEnumId:aeii.typeEnumId,
                            releaseDateTime:(new Timestamp(checkTime + (lockForSeconds*1000)))]).call()
                }
            }
        } finally {
            if (!alreadyDisabled) enableAuthz()
        }

        // tarpit enabled already checked, if authz not enabled return true immediately
        if (!isAuthzEnabled(aeii.typeEnumId)) return true

        // if last was an always allow, then don't bother checking for deny/etc
        if (lastAeii != null && lastAeii.authorizationInheritable && lastAeii.authorizedUserId == userId &&
                lastAeii.authorizedAuthzTypeId == "AUTHZT_ALWAYS" &&
                (lastAeii.authorizedActionEnumId == "AUTHZA_ALL" || lastAeii.authorizedActionEnumId == aeii.actionEnumId)) {
            aeii.copyAuthorizedInfo(lastAeii)
            return true
        }

        EntityList aacvList
        EntityValue denyAacv = null

        // don't check authz for these queries, would cause infinite recursion
        alreadyDisabled = disableAuthz()
        try {
            // check authorizations for those groups (separately cached for more cache hits)
            EntityFind aacvFind = eci.entity.makeFind("ArtifactAuthzCheckView")
                    .condition("artifactTypeEnumId", aeii.typeEnumId)
                    .condition(eci.entity.conditionFactory.makeCondition(
                        eci.entity.conditionFactory.makeCondition("artifactName", ComparisonOperator.EQUALS, aeii.name),
                        JoinOperator.OR,
                        eci.entity.conditionFactory.makeCondition("nameIsPattern", ComparisonOperator.EQUALS, "Y")))
            if (userGroupIdSet.size() == 1) aacvFind.condition("userGroupId", userGroupIdSet.iterator().next())
            else aacvFind.condition("userGroupId", ComparisonOperator.IN, userGroupIdSet)
            if (aeii.actionEnumId) aacvFind.condition("authzActionEnumId", ComparisonOperator.IN, [aeii.actionEnumId, "AUTHZA_ALL"])
            else aacvFind.condition("authzActionEnumId", "AUTHZA_ALL")

            aacvList = aacvFind.useCache(true).list()

            if (aacvList.size() > 0) {
                for (EntityValue aacv in aacvList) {
                    if (aacv.nameIsPattern == "Y" && !aeii.name.matches((String) aacv.artifactName)) continue
                    // check the record-level permission
                    if (aacv.viewEntityName) {
                        EntityValue artifactAuthzRecord = eci.entity.makeFind("ArtifactAuthzRecord")
                                .condition("artifactAuthzId", aacv.artifactAuthzId).useCache(true).one()
                        EntityDefinition ed = eci.entity.getEntityDefinition((String) aacv.viewEntityName)
                        EntityFind ef = eci.entity.makeFind((String) aacv.viewEntityName)
                        if (artifactAuthzRecord.userIdField) {
                            ef.condition((String) artifactAuthzRecord.userIdField, userId)
                        } else if (ed.isField("userId")) {
                            ef.condition("userId", userId)
                        }
                        if (artifactAuthzRecord.filterByDate == "Y") {
                            ef.conditionDate((String) artifactAuthzRecord.filterByDateFromField,
                                    (String) artifactAuthzRecord.filterByDateThruField, eci.user.nowTimestamp)
                        }
                        EntityList condList = eci.entity.makeFind("ArtifactAuthzRecordCond")
                                .condition("artifactAuthzId", aacv.artifactAuthzId).useCache(true).list()
                        for (EntityValue cond in condList) {
                            String expCondValue = eci.resource.evaluateStringExpand((String) cond.condValue,
                                    "ArtifactAuthzRecordCond.${cond.artifactAuthzId}.${cond.artifactAuthzCondSeqId}")
                            if (expCondValue) {
                                ef.condition((String) cond.fieldName,
                                        eci.entity.conditionFactory.comparisonOperatorFromEnumId((String) cond.operatorEnumId),
                                        expCondValue)
                            }
                        }

                        // anything found? if not it fails this condition, so skip the authz
                        if (ef.useCache(true).count() == 0) continue
                    }

                    String authzTypeEnumId = aacv.authzTypeEnumId
                    if (aacv.authzServiceName) {
                        Map result = eci.service.sync().name((String) aacv.authzServiceName).parameters((Map<String, Object>)
                                [userId:userId, authzActionEnumId:aeii.actionEnumId,
                                artifactTypeEnumId:aeii.typeEnumId, artifactName:aeii.name]).call()
                        if (result?.authzTypeEnumId) authzTypeEnumId = result.authzTypeEnumId
                    }

                    if (authzTypeEnumId == "AUTHZT_DENY") {
                        // we already know last was not always allow (checked above), so keep going in loop just in case we
                        // find an always allow in the query
                        denyAacv = aacv
                    } else if (authzTypeEnumId == "AUTHZT_ALWAYS") {
                        aeii.copyAacvInfo(aacv, userId)
                        return true
                    } else if (authzTypeEnumId == "AUTHZT_ALLOW" && denyAacv == null) {
                        // see if there are any denies in AEIs on lower on the stack
                        boolean ancestorDeny = false
                        for (ArtifactExecutionInfoImpl ancestorAeii in artifactExecutionInfoStack)
                            if (ancestorAeii.authorizedAuthzTypeId == "AUTHZT_DENY") ancestorDeny = true

                        if (!ancestorDeny) {
                            aeii.copyAacvInfo(aacv, userId)
                            return true
                        }
                    }
                }
            }

            if (denyAacv != null) {
                // record that this was an explicit deny (for push or exception in case something catches and handles it)
                aeii.copyAacvInfo(denyAacv, userId)

                if (!requiresAuthz || this.disableAuthz) {
                    // if no authz required, just return true even though it was a failure
                    return true
                } else {
                    StringBuilder warning = new StringBuilder()
                    warning.append("User [${userId}] is not authorized for ${aeii.typeEnumId} [${aeii.name}] because of a deny record [type:${aeii.typeEnumId},action:${aeii.actionEnumId}], here is the current artifact stack:")
                    for (def warnAei in this.stack) warning.append("\n").append(warnAei)
                    logger.warn(warning.toString())

                    eci.service.sync().name("create", "ArtifactAuthzFailure").parameters((Map<String, Object>)
                            [artifactName:aeii.name, artifactTypeEnumId:aeii.typeEnumId,
                            authzActionEnumId:aeii.actionEnumId, userId:userId,
                            failureDate:new Timestamp(System.currentTimeMillis()), isDeny:"Y"]).call()

                    return false
                }
            } else {
                // no perms found for this, only allow if the current AEI has inheritable auth and same user, and (ALL action or same action)
                if (lastAeii != null && lastAeii.authorizationInheritable && lastAeii.authorizedUserId == userId &&
                        (lastAeii.authorizedActionEnumId == "AUTHZA_ALL" || lastAeii.authorizedActionEnumId == aeii.actionEnumId)) {
                    aeii.copyAuthorizedInfo(lastAeii)
                    return true
                }
            }

            if (!requiresAuthz || this.disableAuthz) {
                // if no authz required, just push it even though it was a failure
                if (lastAeii != null && lastAeii.authorizationInheritable) aeii.copyAuthorizedInfo(lastAeii)
                return true
            } else {
                // if we got here no authz found, blow up
                StringBuilder warning = new StringBuilder()
                warning.append("User [${userId}] is not authorized for ${aeii.typeEnumId} [${aeii.name}] because of no allow record [type:${aeii.typeEnumId},action:${aeii.actionEnumId}]\nlastAeii=[${lastAeii}]\nHere is the artifact stack:")
                for (def warnAei in this.stack) warning.append("\n").append(warnAei)
                logger.warn(warning.toString(), new Exception("Authz failure location"))

                // NOTE: this is called sync because failures should be rare and not as performance sensitive, and
                //  because this is still in a disableAuthz block (if async a service would have to be written for that)
                eci.service.sync().name("create", "ArtifactAuthzFailure").parameters((Map<String, Object>)
                        [artifactName:aeii.name, artifactTypeEnumId:aeii.typeEnumId,
                        authzActionEnumId:aeii.actionEnumId, userId:userId,
                        failureDate:new Timestamp(System.currentTimeMillis()), isDeny:"N"]).call()

                return false
            }
        } finally {
            if (!alreadyDisabled) enableAuthz()
        }

        return true
    }
}