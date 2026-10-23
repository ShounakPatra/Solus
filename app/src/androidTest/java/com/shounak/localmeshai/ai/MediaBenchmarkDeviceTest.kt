package com.shounak.localmeshai.ai

import androidx.test.platform.app.InstrumentationRegistry
import com.shounak.localmeshai.models.*
import com.shounak.localmeshai.utils.PortableModelBundle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MediaBenchmarkDeviceTest {
    @Test fun displayFramesAreSampledIndependentlyOfTokenArrival() = runBlocking {
        BenchmarkFrames.measure {
            delay(1000)
            val monitor=currentCoroutineContext()[BenchmarkFrames]!!
            assertTrue(monitor.measured())
            assertTrue(synchronized(monitor.cpu) { monitor.cpu.all { it > 0 } })
        }
    }
    @Test fun realMediaPipelineProducesLatencyResults() = runBlocking {
        val path=InstrumentationRegistry.getArguments().getString("mediaBenchmarkPath")
        assumeTrue("Supply mediaBenchmarkPath and complete model companions", !path.isNullOrBlank())
        val file=File(requireNotNull(path))
        val format=requireNotNull(PortableModelBundle.format(file.name))
        val caps=PortableModelBundle.inspect(file,format)
        val bundle=ModelBundle("device-benchmark",file.name,listOf(ModelArtifact(ArtifactRole.Main,file.name,
            ArtifactSource.AppManaged(file.path),file.length())),capabilities=caps,format=format)
        val model=ModelInfo(bundle.id,file.name,"Unknown",ModelStatus.Available,ModelType.Vision,file.name,
            description="",backend=format.backendLabel,deviceTarget="",localPath=file.path,bundle=bundle)
        val result=MediaBenchmarkRunner.run(InstrumentationRegistry.getInstrumentation().targetContext,model,{})
        assertTrue(requireNotNull(result.taskLatencyMedianMs) > 0)
        assertFalse(result.uiFramesMeasured)
    }
}
