package cl.innova.nfc.protocol

import org.junit.Assert.*
import org.junit.Test

class NfcProtocolTest {
    private val demo = CardCredential(1, requireNotNull(NfcId.parse(NfcProtocol.DEMO_PERSON_ID)))

    @Test fun commandsMatchWireContract() {
        assertEquals("00A4040008F0494E4E4F56410100", NfcProtocol.toHex(NfcProtocol.selectAid()))
        assertEquals("80CA000000", NfcProtocol.toHex(NfcProtocol.getPersonId()))
        assertEquals(NfcProtocol.Command.Select, NfcProtocol.parseCommand(NfcProtocol.selectAid()))
        assertEquals(NfcProtocol.Command.Select, NfcProtocol.parseCommand(NfcProtocol.selectAid().dropLast(1).toByteArray()))
    }

    @Test fun sessionRequiresSelectAndResets() {
        val session = CardSession { demo }
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.getPersonId())))
        NfcProtocol.parseSelectResponse(session.process(NfcProtocol.selectAid()))
        assertEquals(NfcProtocol.DEMO_PERSON_ID, NfcProtocol.parsePersonIdResponse(session.process(NfcProtocol.getPersonId())))
        session.reset()
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.getPersonId())))
    }

    @Test fun malformedCommandsHaveDefinedStatusWords() {
        val session = CardSession { demo }
        val cases = mapOf("" to "6700", "00A40400" to "6700", "00A4040008FF" to "6700",
            "00A4040001FF" to "6A82", "80CA0000" to "6700", "80CA00000000" to "6700",
            "80CA010000" to "6D00", "80FF000000" to "6D00", "00CA000000" to "6D00")
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, NfcProtocol.toHex(session.process(NfcProtocol.fromHex(input))))
        }
    }

    @Test fun rejectedSelectClearsSelection() {
        val session = CardSession { demo }
        session.process(NfcProtocol.selectAid())
        session.process(NfcProtocol.fromHex("00A4040001FF"))
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.getPersonId())))
    }

    @Test fun responseValidationRejectsBadStatusAndLength() {
        listOf(byteArrayOf(), byteArrayOf(0x90.toByte()), NfcProtocol.response(NfcProtocol.NOT_FOUND),
            NfcProtocol.response(NfcProtocol.SUCCESS, ByteArray(15)),
            NfcProtocol.response(NfcProtocol.SUCCESS, ByteArray(17))).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { NfcProtocol.parsePersonIdResponse(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NfcProtocol.parseSelectResponse(NfcProtocol.response(NfcProtocol.SUCCESS, byteArrayOf(1)))
        }
        assertEquals(0x6D00, NfcProtocol.parseResponse(NfcProtocol.fromHex("6D00")).status)
    }

    @Test fun verifiedCredentialsHaveTheSameWireFormatForAllThreeAccounts() {
        val tokens = listOf(NfcProtocol.DEMO_PERSON_ID, "00000000000000000000000000000002", "00000000000000000000000000000003")
        tokens.forEachIndexed { generation, hex ->
            val credential = CardCredential(generation.toLong(), requireNotNull(NfcId.parse(hex.lowercase())))
            val session = CardSession { credential }
            assertEquals("9000", NfcProtocol.toHex(session.process(NfcProtocol.selectAid())))
            val result = NfcProtocol.parseResponse(session.process(NfcProtocol.getPersonId()))
            assertEquals(0x9000, result.status)
            assertEquals(16, result.data.size)
            assertEquals(hex, NfcProtocol.toHex(result.data))
        }
    }

    @Test fun missingCredentialNeverFallsBackToAntonia() {
        var credential: CardCredential? = null
        val session = CardSession { credential }
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.selectAid())))
        credential = demo
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.getPersonId())))
        assertEquals("9000", NfcProtocol.toHex(session.process(NfcProtocol.selectAid())))
        credential = null
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.getPersonId())))
    }

    @Test fun staleSelectCannotReturnTheNewAccountOrTheSameIdAfterSwitchingBack() {
        var current = demo
        val session = CardSession { current }
        session.process(NfcProtocol.selectAid())
        current = CardCredential(2, requireNotNull(NfcId.parse("00000000000000000000000000000002")))
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.getPersonId())))
        current = demo
        session.process(NfcProtocol.selectAid())
        current = CardCredential(3, requireNotNull(NfcId.parse("00000000000000000000000000000002")))
        current = CardCredential(4, demo.id)
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.getPersonId())))
        assertEquals("9000", NfcProtocol.toHex(session.process(NfcProtocol.selectAid())))
        assertEquals(NfcProtocol.DEMO_PERSON_ID, NfcProtocol.parsePersonIdResponse(session.process(NfcProtocol.getPersonId())))
    }

    @Test fun expiredCredentialBetweenSelectAndGetIsRejected() {
        var now = 0
        val session = CardSession { demo.takeIf { now < 5 } }
        assertEquals("9000", NfcProtocol.toHex(session.process(NfcProtocol.selectAid())))
        now = 5
        assertEquals("6A82", NfcProtocol.toHex(session.process(NfcProtocol.getPersonId())))
    }

    @Test fun credentialParserRejectsMalformedOrWrongSizedIds() {
        listOf("F".repeat(31), "F".repeat(33), "G".repeat(32), " " + "F".repeat(31)).forEach {
            assertNull(NfcId.parse(it))
        }
    }

    @Test fun hexRoundTripsAndRejectsInvalidInput() {
        assertEquals("00FF8090", NfcProtocol.toHex(NfcProtocol.fromHex("00ff8090")))
        listOf("F", "GG").forEach { hex ->
            assertThrows(IllegalArgumentException::class.java) { NfcProtocol.fromHex(hex) }
        }
    }
}
