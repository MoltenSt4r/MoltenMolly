package im.molly.plugins.ipc;

import im.molly.plugins.ipc.PluginContactDto;
import im.molly.plugins.ipc.PluginFileResultDto;
import im.molly.plugins.ipc.PluginHttpRequestDto;
import im.molly.plugins.ipc.PluginHttpResponseDto;
import im.molly.plugins.ipc.PluginMessageDto;
import im.molly.plugins.ipc.PluginResultDto;

/**
 * Host IPC interface exposed by the main Molly process to the `:plugin_sandbox` process.
 * Every call is permission-checked against PluginGrantStore on the main-process side
 * and recorded in PluginAuditJournal before touching any Molly data or network.
 */
interface IPluginHost {
    boolean hasPermission(String pluginId, String permission);

    List<PluginMessageDto> readMessages(String pluginId, long threadId, int limit, long beforeTimestampMs);

    PluginResultDto sendMessage(String pluginId, long threadId, String body, long replyToMessageId);

    List<PluginContactDto> readContacts(String pluginId, String query, int limit);

    PluginHttpResponseDto executeHttpRequest(String pluginId, in PluginHttpRequestDto request);

    PluginFileResultDto readPluginFile(String pluginId, String relativePath);

    PluginResultDto writePluginFile(String pluginId, String relativePath, in byte[] data);

    String getSetting(String pluginId, String key, String defaultValue);

    PluginResultDto setSetting(String pluginId, String key, String value);

    PluginResultDto postUiNotice(String pluginId, String title, String message);
}
