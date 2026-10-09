package kr.stillwatch.app
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class DomainTest {
    @Test fun unknownNeverBecomesSafe() { assertEquals(AlertState.UNKNOWN, AlertState.parse("future")) }
    @Test fun lossIsDistinctFromRescue() { assertEquals(AlertState.SENSOR_LOST,AlertState.parse("monitoring_interrupted")); assertNotEquals(AlertState.RESCUE,AlertState.SENSOR_LOST) }
    @Test fun zeroAndMissingPeopleDiffer() { assertEquals("0명",countText(0));assertEquals("미확인",countText(null));assertEquals("미확인",countText(-1)) }
    @Test fun timestampsNeedTimezone() { assertEquals("미확인",timeText("2026-10-09T14:00:00"));assertEquals("10.09 14:00:00",timeText("2026-10-09T05:00:00Z")) }
    @Test fun staleOrFutureSignalsNotFresh() { val now=Instant.parse("2026-10-09T05:00:00Z");assertFalse(isFresh(null,now));assertFalse(isFresh("2026-10-09T04:59:00Z",now));assertFalse(isFresh("2026-10-09T05:00:01Z",now));assertTrue(isFresh("2026-10-09T04:59:50Z",now)) }
    @Test fun managerCannotImpersonateWorkerResponse() { assertFalse(canWorkerRespond(Role.MANAGER,AlertState.STILL_CHECK,true));assertFalse(canWorkerRespond(Role.WORKER,AlertState.STILL_CHECK,false));assertTrue(canWorkerRespond(Role.WORKER,AlertState.STILL_CHECK,true));assertFalse(canWorkerRespond(Role.WORKER,AlertState.RESCUE,true)) }
}
