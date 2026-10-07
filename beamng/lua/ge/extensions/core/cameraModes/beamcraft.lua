-- BeamCraft: first/third person camera that follows Steve (position comes from Minecraft).

local C = {}
C.__index = C

function C:init()
  self.isGlobal = true
  self.hidden = true
end

function C:setCustomData(customData)
end

function C:reset()
end

function C:update(data)
  if beamcraft and beamcraft.updateCamera then
    return beamcraft.updateCamera(data)
  end
  return false
end

-- DO NOT CHANGE CLASS IMPLEMENTATION BELOW

return function(...)
  local o = ... or {}
  setmetatable(o, C)
  o:init()
  return o
end
