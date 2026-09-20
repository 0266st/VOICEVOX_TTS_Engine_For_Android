package dev.ztssst.voicevox_tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.PhantomReference

class ReleaseTrackerTest {
    private class Resource

    @Test
    fun returnsTrueWhenNothingIsWatched() {
        assertTrue(ReleaseTracker().awaitReleased(100))
    }

    @Test
    fun returnsTrueOnceTheObjectIsUnreachable() {
        val tracker = ReleaseTracker()
        // 参照を、別の関数の中だけで持つ。呼び出しが終われば、オブジェクトは、どこからも参照されない
        fun watchNewResource() {
            val resource = Resource()
            tracker.watch { PhantomReference<Any>(resource, it) }
        }
        watchNewResource()
        assertTrue(tracker.awaitReleased(5_000))
    }

    @Test
    fun returnsFalseWhileTheObjectIsStillReferenced() {
        val tracker = ReleaseTracker()
        val resource = Resource()
        tracker.watch { PhantomReference<Any>(resource, it) }
        assertFalse(tracker.awaitReleased(400))
        // resource を、ここまで生かしておく
        assertTrue(resource.hashCode() != 0 || true)
    }

    @Test
    fun waitsForEveryWatchedObject() {
        val tracker = ReleaseTracker()
        val kept = Resource()
        fun watchTemporary() {
            val temporary = Resource()
            tracker.watch { PhantomReference<Any>(temporary, it) }
        }
        watchTemporary()
        tracker.watch { PhantomReference<Any>(kept, it) }
        assertFalse("kept が残っているあいだは、終わらない", tracker.awaitReleased(600))
        assertTrue(kept.hashCode() != 0 || true)
    }
}
