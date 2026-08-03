package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

class WaitlistControllerRoutesTest {
    @Test
    fun `entry and offer controller exposes planned staff routes`() {
        classRoute(WaitlistController::class.java) shouldBeEqualTo "/api/{tenantCode}/clinics/{clinicId}/waitlist"

        postRoute(WaitlistController::class.java, "createEntry") shouldBeEqualTo "/entries"
        getRoute(WaitlistController::class.java, "listEntries") shouldBeEqualTo "/entries"
        getRoute(WaitlistController::class.java, "getEntry") shouldBeEqualTo "/entries/{entryRef}"
        postRoute(WaitlistController::class.java, "withdrawEntry") shouldBeEqualTo "/entries/{entryRef}/withdraw"
        getRoute(WaitlistController::class.java, "listOffers") shouldBeEqualTo "/offers"
        getRoute(WaitlistController::class.java, "getOffer") shouldBeEqualTo "/offers/{offerRef}"
        postRoute(WaitlistController::class.java, "confirmOffer") shouldBeEqualTo "/offers/{offerRef}/confirm"
        postRoute(WaitlistController::class.java, "declineOffer") shouldBeEqualTo "/offers/{offerRef}/decline"
        getRoute(WaitlistController::class.java, "offerDecision") shouldBeEqualTo "/offers/{offerRef}/decision"
    }

    @Test
    fun `policy and operations controllers expose planned staff routes`() {
        classRoute(WaitlistPolicyController::class.java) shouldBeEqualTo
            "/api/{tenantCode}/clinics/{clinicId}/waitlist/policies"
        getRoute(WaitlistPolicyController::class.java, "activePolicy") shouldBeEqualTo "/active"
        getRoute(WaitlistPolicyController::class.java, "getPolicy") shouldBeEqualTo "/{policyRef}"
        postRoute(WaitlistPolicyController::class.java, "upsertPolicy") shouldBeEqualTo ""
        postRoute(WaitlistPolicyController::class.java, "activatePolicy") shouldBeEqualTo "/{policyRef}/activate"

        classRoute(WaitlistOperationsController::class.java) shouldBeEqualTo
            "/api/{tenantCode}/clinics/{clinicId}/waitlist"
        postRoute(WaitlistOperationsController::class.java, "createRestriction") shouldBeEqualTo "/restrictions"
        postRoute(WaitlistOperationsController::class.java, "releaseRestriction") shouldBeEqualTo
            "/restrictions/{restrictionRef}/release"
        postRoute(WaitlistOperationsController::class.java, "grantRecoveryCredit") shouldBeEqualTo "/recovery-credits"
        postRoute(WaitlistOperationsController::class.java, "revokeRecoveryCredit") shouldBeEqualTo
            "/recovery-credits/{recoveryCreditRef}/revoke"
        postRoute(WaitlistOperationsController::class.java, "createBenefitGrant") shouldBeEqualTo "/benefit-grants"
        postRoute(WaitlistOperationsController::class.java, "revokeBenefitGrant") shouldBeEqualTo
            "/benefit-grants/{benefitGrantRef}/revoke"
    }

    private fun classRoute(type: Class<*>): String =
        type.getAnnotation(RequestMapping::class.java).value.single()

    private fun getRoute(type: Class<*>, methodName: String): String =
        type.declaredMethods.single { it.name == methodName }
            .getAnnotation(GetMapping::class.java)
            .value
            .singleOrNull()
            ?: ""

    private fun postRoute(type: Class<*>, methodName: String): String =
        type.declaredMethods.single { it.name == methodName }
            .getAnnotation(PostMapping::class.java)
            .value
            .singleOrNull()
            ?: ""
}
