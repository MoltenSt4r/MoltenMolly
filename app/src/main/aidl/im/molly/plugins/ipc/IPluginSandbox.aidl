package im.molly.plugins.ipc;

import android.os.Bundle;
import im.molly.plugins.ipc.IPluginHost;
import im.molly.plugins.ipc.PluginHealthDto;
import im.molly.plugins.ipc.PluginManifestDto;
import im.molly.plugins.ipc.PluginResultDto;

/**
 * Sandbox IPC interface exposed by PluginSandboxService in the `:plugin_sandbox` process.
 */
interface IPluginSandbox {
    void initialize(in IPluginHost host);

    PluginResultDto loadPlugin(in PluginManifestDto manifest, String sourceCode);

    PluginResultDto unloadPlugin(String pluginId);

    PluginResultDto setPluginEnabled(String pluginId, boolean enabled);

    PluginResultDto dispatchEvent(String pluginId, String eventType, in Bundle payload);

    PluginHealthDto getHealth();

    void shutdown();
}
