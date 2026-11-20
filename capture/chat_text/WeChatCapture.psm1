function Initialize-WeChatCapture {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, Accessibility
    if ('AutoReply.WeChatMessages' -as [type]) {
        return
    }

    Add-Type -ReferencedAssemblies ([Accessibility.IAccessible].Assembly.Location) -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Accessibility;

namespace AutoReply
{
    public static class WeChatMessages
    {
        private const uint ClientObject = 0xFFFFFFFC;
        private const int ListRole = 33;
        private const int ListItemRole = 34;
        private const int PaneRole = 16;
        private const int PushButtonRole = 43;
        private const int TextRole = 42;

        public sealed class Conversation
        {
            public string Title { get; set; }
            public MessageRow[] Rows { get; set; }
        }

        public sealed class MessageRow
        {
            public string Text { get; private set; }
            public string Direction { get; private set; }
            public int[] ImageBounds { get; set; }

            public MessageRow(string text, string direction)
            {
                Text = text;
                Direction = direction;
            }
        }

        [DllImport("oleacc.dll")]
        private static extern int AccessibleObjectFromWindow(IntPtr window, uint objectId,
            ref Guid interfaceId, [MarshalAs(UnmanagedType.Interface)] out IAccessible result);

        [DllImport("oleacc.dll")]
        private static extern int AccessibleChildren(IAccessible parent, int start, int count,
            [Out, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 2)] object[] children, out int fetched);

        public static Conversation Read(IntPtr window, bool includeImages)
        {
            var interfaceId = typeof(IAccessible).GUID;
            IAccessible client;
            if (AccessibleObjectFromWindow(window, ClientObject, ref interfaceId, out client) != 0)
                throw new InvalidOperationException("WeChat did not expose an MSAA client object.");

            var lists = new List<IAccessible>();
            var textFields = new List<IAccessible>();
            FindConversationControls(client, lists, textFields, 0);
            if (lists.Count != 1)
                throw new InvalidOperationException("Expected one active message list. Found " + lists.Count + ".");

            var rows = new List<MessageRow>();
            foreach (var entry in Children(lists[0]))
            {
                var node = entry as IAccessible;
                object childId = node == null ? entry : 0;
                var owner = node ?? lists[0];
                if (Convert.ToInt32(owner.get_accRole(childId)) != ListItemRole)
                    continue;
                var row = new MessageRow(owner.get_accName(childId) ?? String.Empty, ReadDirection(node));
                if (includeImages && node != null && (row.Text == "[\u56fe\u7247]" || row.Text == "[Image]"))
                    row.ImageBounds = FindImageBounds(node, lists[0]);
                rows.Add(row);
            }
            return new Conversation { Title = ReadTitle(lists[0], textFields), Rows = rows.ToArray() };
        }

        private static int[] FindImageBounds(IAccessible row, IAccessible list)
        {
            try
            {
                int left, top, width, height;
                list.accLocation(out left, out top, out width, out height, 0);
                var buttons = new List<int[]>();
                FindImageButtons(row, buttons, 0);
                if (buttons.Count != 1)
                    return null;
                var b = buttons[0];
                return b[0] >= left && b[1] >= top && (long)b[0] + b[2] <= (long)left + width &&
                    (long)b[1] + b[3] <= (long)top + height ? b : null;
            }
            catch (COMException)
            {
                return null;
            }
        }

        private static void FindImageButtons(IAccessible node, List<int[]> buttons, int depth)
        {
            if (depth > 10)
                return;
            if (depth >= 4 && Convert.ToInt32(node.get_accRole(0)) == PushButtonRole &&
                String.IsNullOrEmpty(node.get_accName(0)))
            {
                int x, y, width, height;
                node.accLocation(out x, out y, out width, out height, 0);
                if (width > 0 && height > 0)
                    buttons.Add(new[] {x, y, width, height});
            }
            foreach (var entry in Children(node))
            {
                var child = entry as IAccessible;
                if (child != null)
                    FindImageButtons(child, buttons, depth + 1);
            }
        }

        private static string ReadTitle(IAccessible list, List<IAccessible> textFields)
        {
            // WeChat uses the conversation title as the accessible name of its composer.
            try
            {
                int left, top, width, height;
                list.accLocation(out left, out top, out width, out height, 0);
                if (width <= 0 || height <= 0)
                    return null;

                string title = null;
                foreach (var field in textFields)
                {
                    int x, y, fieldWidth, fieldHeight;
                    field.accLocation(out x, out y, out fieldWidth, out fieldHeight, 0);
                    if (fieldHeight <= 0 || fieldWidth != width || x != left || y < (long)top + height)
                        continue;

                    var name = field.get_accName(0);
                    if (String.IsNullOrWhiteSpace(name))
                        continue;
                    if (title != null && title != name)
                        return null;
                    title = name;
                }
                return title;
            }
            catch (COMException)
            {
                return null;
            }
        }

        private static string ReadDirection(IAccessible row)
        {
            if (row == null)
                return "unknown";

            try
            {
                return ReadAvatarSide(row);
            }
            catch (COMException)
            {
                // Some loaded rows expose text before their layout is available.
                return "unknown";
            }
        }

        private static string ReadAvatarSide(IAccessible row)
        {
            int left, top, width, height;
            row.accLocation(out left, out top, out width, out height, 0);
            if (width <= 0 || height <= 0)
                return "unknown";

            // In this WeChat layout the avatar is a button directly under the row's content pane.
            IAccessible pane = null;
            foreach (var entry in Children(row))
            {
                var child = entry as IAccessible;
                if (child == null || Convert.ToInt32(child.get_accRole(0)) != PaneRole)
                    continue;
                if (pane != null)
                    return "unknown";
                pane = child;
            }
            if (pane == null)
                return "unknown";

            string direction = "unknown";
            int buttonCount = 0;
            foreach (var entry in Children(pane))
            {
                var child = entry as IAccessible;
                var owner = child ?? pane;
                object childId = child == null ? entry : 0;
                if (Convert.ToInt32(owner.get_accRole(childId)) != PushButtonRole)
                    continue;
                if (++buttonCount > 1)
                    return "unknown";

                int x, y, buttonWidth, buttonHeight;
                owner.accLocation(out x, out y, out buttonWidth, out buttonHeight, childId);
                double center = ((double)x - left + buttonWidth / 2.0) / width;
                if (buttonWidth <= 0 || buttonHeight <= 0 || center < 0 || center > 1 ||
                    y < top || (long)y + buttonHeight > (long)top + height)
                    continue;
                if (center <= 0.25)
                    direction = "incoming";
                else if (center >= 0.75)
                    direction = "outgoing";
            }
            return direction;
        }

        private static void FindConversationControls(IAccessible node, List<IAccessible> lists,
            List<IAccessible> textFields, int depth)
        {
            var role = Convert.ToInt32(node.get_accRole(0));
            if (role == ListRole)
            {
                var name = node.get_accName(0);
                if (name == "\u6d88\u606f" || name == "Messages")
                    lists.Add(node);
                return;
            }

            if (role == TextRole)
                textFields.Add(node);

            if (depth >= 24)
                return;

            foreach (var entry in Children(node))
            {
                var child = entry as IAccessible;
                if (child != null)
                    FindConversationControls(child, lists, textFields, depth + 1);
            }
        }

        private static IEnumerable<object> Children(IAccessible parent)
        {
            int count = parent.accChildCount;
            if (count <= 0)
                yield break;

            var children = new object[count];
            int fetched;
            if (AccessibleChildren(parent, 0, count, children, out fetched) < 0)
                yield break;

            for (int index = 0; index < fetched; index++)
                yield return children[index];
        }
    }
}
'@
}

function Get-WeChatConversation {
    [CmdletBinding()]
    param([switch]$IncludeImageTargets)

    $ErrorActionPreference = 'Stop'
    Initialize-WeChatCapture

    $desktop = [System.Windows.Automation.AutomationElement]::RootElement
    $windows = @(foreach ($process in (Get-Process -Name WeChat, Weixin -ErrorAction SilentlyContinue)) {
        $filter = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $process.Id)
        foreach ($window in $desktop.FindAll('Children', $filter)) {
            if ($window.Current.ClassName -eq 'WeChatMainWndForPC') {
                $window
            }
        }
    })
    
    if ($windows.Count -ne 1) {
        throw "Expected one open WeChat main window. Found $($windows.Count)."
    }

    $handle = $windows[0].Current.NativeWindowHandle
    $conversation = [AutoReply.WeChatMessages]::Read([IntPtr]$handle, $IncludeImageTargets.IsPresent)
    $rows = $conversation.Rows
    $records = @(for ($index = 0; $index -lt $rows.Count; $index++) {
        $record = [ordered]@{
            Index = $index + 1
            Text = $rows[$index].Text
            Direction = $rows[$index].Direction
        }
        if ($IncludeImageTargets -and $rows[$index].ImageBounds) {
            $record.ImageBounds = $rows[$index].ImageBounds
        }
        [pscustomobject]$record
    })

    $snapshot = [ordered]@{
        Source = 'wechat'
        CapturedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        ConversationTitle = $conversation.Title
        Records = $records
    }
    if ($IncludeImageTargets) {
        $snapshot.WindowHandle = $handle
    }
    [pscustomobject]$snapshot
}

Export-ModuleMember -Function Get-WeChatConversation
