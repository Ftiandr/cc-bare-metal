-- Used by "lua FILE ARGS..." to start the CraftOS shell in the Unix working directory, so the
-- program path and shell.resolve() inside it are relative to it (CraftOS itself always starts in "/").
local args = { ... }
local dir = table.remove(args, 1)
if dir and fs.isDir(dir) then shell.setDir(dir) end
if #args == 0 then return true end
return shell.run(table.unpack(args))
