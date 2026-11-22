package com.xiaqui.ipmsg

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LanSessionControllerTest {
    @Test fun foregroundAndBackgroundOwnersShareOneSession() {
        val live=AtomicInteger(); val starts=AtomicInteger(); val stops=AtomicInteger()
        val controller=LanSessionController { object : LanSession {
            override fun start() { assertEquals(1,live.incrementAndGet());starts.incrementAndGet() }
            override fun stop() { stops.incrementAndGet() }
            override fun awaitStopped() { live.decrementAndGet() }
        } }
        try {
            val activity=Any();val service=Any()
            controller.foreground(activity,true);controller.awaitIdle()
            controller.background(service,true);controller.awaitIdle()
            controller.foreground(activity,false);controller.awaitIdle()
            assertEquals(1,starts.get());assertEquals(0,stops.get())
            controller.foreground(activity,true);controller.pauseBackground();controller.awaitIdle()
            assertEquals(0,stops.get()) // Notification pause while visible keeps communication.
            controller.foreground(activity,false);controller.awaitIdle()
            assertEquals(0,live.get());assertTrue(stops.get()>0)
            controller.foreground(activity,true);controller.awaitIdle()
            assertEquals(2,starts.get());assertEquals(1,live.get())
        } finally { controller.shutdown() }
    }

    @Test fun rapidRecreationWaitsForOldWritesBeforeRebinding() {
        val drainEntered=CountDownLatch(1);val finishDrain=CountDownLatch(1)
        val starts=AtomicInteger();val live=AtomicInteger()
        val controller=LanSessionController { object : LanSession {
            private var stopped=false
            override fun start() { assertEquals(1,live.incrementAndGet());starts.incrementAndGet() }
            override fun stop() { stopped=true }
            override fun awaitStopped() {
                assertTrue(stopped);drainEntered.countDown()
                check(finishDrain.await(10,TimeUnit.SECONDS));live.decrementAndGet()
            }
        } }
        try {
            val old=Any();val next=Any()
            controller.foreground(old,true);controller.awaitIdle()
            controller.foreground(old,false)
            assertTrue(drainEntered.await(10,TimeUnit.SECONDS))
            controller.foreground(next,true);controller.foreground(old,false)
            assertEquals(1,starts.get())
            finishDrain.countDown();controller.awaitIdle()
            assertEquals(2,starts.get());assertEquals(1,live.get())
            repeat(20) { controller.background(Any(),true);controller.pauseBackground() }
            controller.awaitIdle();assertEquals(2,starts.get())
        } finally { finishDrain.countDown();controller.shutdown() }
    }

    @Test fun releaseDuringInitializationCannotPublishAnUnownedSession() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val starts=AtomicInteger();val closed=AtomicInteger()
        val controller=LanSessionController {
            entered.countDown();check(release.await(10,TimeUnit.SECONDS))
            object : LanSession {
                override fun start() { starts.incrementAndGet() }
                override fun stop() { }
                override fun awaitStopped() { closed.incrementAndGet() }
            }
        }
        try {
            val owner=Any();controller.foreground(owner,true)
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            controller.foreground(owner,false);release.countDown();controller.awaitIdle()
            assertEquals(0,starts.get());assertEquals(1,closed.get())
        } finally { release.countDown();controller.shutdown() }
    }

    @Test fun failedStartIsDrainedAndCanBeRetried() {
        val tries=AtomicInteger();val closes=AtomicInteger()
        val controller=LanSessionController { object : LanSession {
            override fun start() { if(tries.incrementAndGet()==1) error("start failed") }
            override fun stop() { }
            override fun awaitStopped() { closes.incrementAndGet() }
        } }
        try {
            val owner=Any();controller.foreground(owner,true);controller.awaitIdle()
            assertTrue(controller.problem.isNotEmpty());assertEquals(1,closes.get())
            controller.foreground(owner,true);controller.awaitIdle()
            assertEquals("",controller.problem);assertEquals(2,tries.get())
        } finally { controller.shutdown() }
    }
}
