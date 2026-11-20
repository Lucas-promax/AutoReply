Option Explicit

Dim files, shell, root, python
Set files = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

root = files.GetParentFolderName(WScript.ScriptFullName)
python = files.BuildPath(root, ".venv\Scripts\pythonw.exe")
If Not files.FileExists(python) Then
    MsgBox "Python environment not found. Set up .venv as described in README.md.", vbExclamation, "AutoReply"
    WScript.Quit 1
End If

shell.CurrentDirectory = root
shell.Run Chr(34) & python & Chr(34) & " -m gui", 1, False
