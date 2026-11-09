package com.model_store.modern.ordering.`case`.user

import com.model_store.modern.ordering.`case`.user.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UserOrderCaseTest {
    @Test
    fun `Java blank and trim contracts preserve nonbreaking spaces and unicode whitespace`() {
        for(value in listOf(null,""," \t\r\n","\u2003")) assertThrows(CaseFailure::class.java) { requireCaseComment(value) }
        assertEquals("\u00a0",requireCaseComment("\u00a0"))
        assertEquals("\u2003reason\u2003",requireCaseComment(" \u2003reason\u2003 "))
        assertEquals("reason",requireCaseComment("\u0000 reason \u0000"))
    }

    @Test
    fun `appeal only buyer party never administrator override and zero negative fractional prepayment`() {
        val base=CaseOrder(1,2,3,"AWAITING_PAYMENT",null,1,10f,0f,"RUB",null)
        base.requireParty(2,true)
        assertThrows(CaseFailure::class.java) { base.requireParty(3,true) }
        assertThrows(CaseFailure::class.java) { base.requireParty(4) }
        for(value in listOf(null,0f,-0f)) base.copy(prepaymentAmount=value).requireOpening(CaseKind.CANCELLATION_REQUEST)
        for(value in listOf(-1f,0.5f,Float.NaN)) {
            assertThrows(CaseFailure::class.java) { base.copy(prepaymentAmount=value).requireOpening(CaseKind.CANCELLATION_REQUEST) }
            base.copy(status="AWAITING_PREPAYMENT",prepaymentAmount=value).requireOpening(CaseKind.CANCELLATION_REQUEST)
        }
    }
}
