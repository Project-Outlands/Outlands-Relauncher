package top.outlands.relauncher;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.spi.LoggerContextFactory;
import top.outlands.relauncher.shared.GameArguments;
import top.outlands.relauncher.shared.LaunchPlatform;
import top.outlands.relauncher.shared.SessionWire;

@IFMLLoadingPlugin.Name("Outlands Relauncher")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions("top.outlands.relauncher.")
public final class RelaunchCorePlugin implements IFMLLoadingPlugin {
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();
    private static final String OUTLANDS_MARKER = "net/minecraftforge/common/LoaderVersion.class";

    @Override
    public void injectData(Map<String, Object> data) {
        if (LaunchPlatform.current() == null
                || FMLLaunchHandler.side() != Side.CLIENT
                || System.getProperty("outlands.relauncher.child") != null
                || Launch.classLoader.getResource(OUTLANDS_MARKER) != null) {
            return;
        }
        Object args = Launch.blackboard.get("forgeLaunchArgs");
        Object location = data.get("mcLocation");
        if (!(args instanceof Map) || !(location instanceof File) || !ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        Map<?, ?> snapshot = new HashMap<>((Map<?, ?>) args);
        File gameDir = (File) location;
        RelaunchOrchestrator orchestrator = new RelaunchOrchestrator(new RelaunchUi(),
                RelaunchCorePlugin.class::getResourceAsStream, ProcessBuilder::start, JavaRuntime::probe,
                JavaRuntime::probeOptions, RelaunchCorePlugin::releaseForgeLogs);
        Integer exit = orchestrator.launch(gameDir.toPath(), instanceDir -> {
            GameArguments session = GameArguments.capture(snapshot, instanceDir);
            return launchProperties -> {
                List<String> resolved = session.resolve(launchProperties);
                return output -> SessionWire.write(output, resolved);
            };
        });
        if (exit != null) {
            // Forge 2860 permits this System.exit path; exitJava uses Runtime.exit and traps mod callers.
            FMLCommonHandler.instance().handleExit(exit);
        }
    }

    private static void releaseForgeLogs() {
        LoggerContextFactory factory = LogManager.getFactory();
        if (!(factory instanceof Log4jContextFactory)) {
            LogManager.shutdown();
            return;
        }
        // LaunchWrapper and Forge can use distinct class-loader contexts for the same log files.
        Log4jContextFactory log4j = (Log4jContextFactory) factory;
        for (LoggerContext context : log4j.getSelector().getLoggerContexts()) {
            context.stop();
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
