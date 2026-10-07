-- BeamCraft: Minecraft runs in the background (BeamCraft Fabric mod) and BeamNG shows the world.
--
-- Minecraft owns Steve: movement physics, inventory, health, blocks, attack damage.
-- BeamNG owns the world: it mirrors the ground around Steve into Minecraft as invisible barriers,
-- renders Steve and every Minecraft block, and turns Minecraft attacks into car damage.
--
-- Coordinates: Minecraft x = BeamNG x + origin.x, Minecraft z = origin.z - BeamNG y,
-- Minecraft y = BeamNG z - origin.y + Y_BASE. Each BeamNG level gets its own far-apart patch of the
-- Minecraft world, so blocks and ground from one map never show up on another.

local M = {}
local logTag = 'BeamCraft'
local im = ui_imgui
local bor, band, bnot = bit.bor, bit.band, bit.bnot

local Y_BASE = 60
local LEVEL_SPACING = 20000
local SETTINGS_DIR = '/settings/beamcraft/'
local SETTINGS_FILE = SETTINGS_DIR .. 'settings.json'
local LEVELS_FILE = SETTINGS_DIR .. 'levels.json'

local defaults = {
  mcPort = 47820,
  bngPort = 47821,
  lookSensitivity = 9.6,  -- 9.6 matches Minecraft's default mouse sensitivity
  invertY = false,
  fov = 70,
  hitImpulse = 70,        -- N*s of push per point of Minecraft damage (diamond sword = 7, netherite axe = 10)
  hitRadius = 0.6,        -- metres around the hit point that get dented
  explosionStrength = 8,  -- N*s per node per point of explosion power (TNT = 4), fading with distance
  terrainRadius = 10,     -- blocks around Steve mirrored into Minecraft
  rayStartHeight = 12,    -- ground rays start this far above Steve's feet (taller things become walls)
  geoCell = 0.25,         -- grid size (m) for sampling BeamNG's real geometry for Steve's collisions
  geoRadius = 3.5,        -- ...and how far around him
  thirdPersonDistance = 4,
  footFpsLimit = 60,      -- cap BeamNG's frame rate while on foot so the GPU has time left for Minecraft's overlay (0 = off)
  screenFpsLimit = 24,    -- ...and while a Minecraft screen (inventory, chest, chat) is open (0 = same as footFpsLimit)
  hudResolution = 0.5,     -- Minecraft renders the overlay at this fraction of BeamNG's resolution (1 = sharper, slower)
}
local cfg = {}

-- network
local udp
local outLines, outLen = {}, 0
local now = 0
local lastHeard = -100
local helloTimer = 0
local mcStatus = 'offline' -- offline | menu | loading | world

-- level mapping
local origin

-- Steve, as reported by Minecraft
local st
local stTime = 0
local snaps = {}   -- recent Minecraft positions {tick, x, y, z}
local renderTick   -- the (fractional) Minecraft tick we are currently showing
local tpGuard
local hotbar = {}
local screenOpen = false
local lastSt = 0

-- on-foot state
local onFoot = false
local look = {yaw = 0, pitch = 0}
local keys = 0
local menuOpen = false
local mouseLocked = false
local target -- {veh = , point = vec3, dir = vec3}
local targetTimer = 0
local safePos
local safeTimer = 0
local unicycleTimer = 0
local parkTimer = 0
local visualZOffset = 0
local message, messageTime = nil, 0

-- terrain mirroring
local terrainTimer = 0
local terrainQueue = {}
local terrainCenter

-- scene objects
local blocks = {}
local loadedTextures = {}
local carsTimer, lastCarHit = 0, -10
local lastCarId -- the car the player last drove
local savedFpsLimit

local appliedFpsLimit
-- The player's own frame-limit setting, kept on disk while BeamCraft has it changed, so it comes
-- back even if BeamNG is closed or crashes in the middle (BeamNG saves the changed value).
local FPS_RESTORE_FILE = '/settings/beamcraft/fpsrestore.json'

-- BeamNG frame cap while BeamCraft needs GPU time (the player's own setting is saved first)
local function applyFpsLimit(limit)
  limit = tonumber(limit) or 0
  if limit <= 0 or limit == appliedFpsLimit then return end
  if not savedFpsLimit then
    savedFpsLimit = jsonReadFile(FPS_RESTORE_FILE) -- left over from a session that didn't end cleanly
      or {enabled = settings.getValue('fpsLimitEnabled'), limit = settings.getValue('fpsLimit')}
    FS:directoryCreate(SETTINGS_DIR, true)
    jsonWriteFile(FPS_RESTORE_FILE, savedFpsLimit, true)
  end
  settings.setValue('fpsLimit', limit)
  settings.setValue('fpsLimitEnabled', true)
  appliedFpsLimit = limit
end

local function restoreFpsLimit()
  appliedFpsLimit = nil
  savedFpsLimit = savedFpsLimit or jsonReadFile(FPS_RESTORE_FILE)
  if not savedFpsLimit then return end
  if savedFpsLimit.limit ~= nil then settings.setValue('fpsLimit', savedFpsLimit.limit) end
  settings.setValue('fpsLimitEnabled', savedFpsLimit.enabled and true or false)
  savedFpsLimit = nil
  if FS:fileExists(FPS_RESTORE_FILE) then FS:removeFile(FPS_RESTORE_FILE) end
end
local sendCars -- defined further down (cars as obstacles)
local updateMenuOpen -- defined further down (is BeamNG's menu up?)
local fpsAvg = 60
local collisionDirty = false
local collisionTimer = 0
local dirtyPoints = {} -- where blocks changed since BeamNG's collision was last rebuilt
local dirtyEverywhere = false
-- Blocks that were there at the last collision rebuild. BeamNG keeps a deleted block's collision
-- (and gives a new block none) until the next rebuild, so these are what its rays can really hit.
local collided = {}

-- Making blocks solid for cars means rebuilding the collision of the whole map (half a second on
-- big maps), so it is batched: only remember where things changed for now.
local function markCollisionDirty(pos)
  collisionDirty = true
  if pos and #dirtyPoints < 256 then dirtyPoints[#dirtyPoints + 1] = vec3(pos.x, pos.y, pos.z)
  else dirtyEverywhere = true end -- too many to track: any moving car triggers the rebuild
end

-- Rebuild only when a car could actually hit a changed block: while driving, or when any car is
-- moving close to one. While walking, Steve's collisions come from Minecraft and don't need it.
local function collisionNeededNow()
  if not onFoot then return true end
  for _, veh in ipairs(getAllVehicles()) do
    if veh:getActive() and veh:getJBeamFilename() ~= 'unicycle' then
      local speed = vec3(veh:getVelocityXYZ()):length()
      if speed > 1.5 then
        if dirtyEverywhere then return true end
        local p = veh:getPosition()
        for _, d in ipairs(dirtyPoints) do
          if p:distance(d) < 40 + speed then return true end
        end
      end
    end
  end
  return false
end
local steve
local hudErrorLogged = false
local layoutRetry
local lastSizeSent
local hudPending, hudShownFrame = {}, -1
local lastHudImage
local originalReloadUI, quickAccessToggle
local hudFramesReceived, diagTimer = 0, 10
local chatOpen = false      -- Minecraft's chat screen is open (it is drawn in the overlay)
local chatPending = 0       -- seconds left waiting for Minecraft to open the chat we asked for
local chatBuf, chatLastText, chatFocus = nil, '', false
local screenTyping = false  -- a text box in a Minecraft screen has focus (creative search, anvil name...)
local typeBuf, typeLast = nil, ''
local disabledMaps = nil
local lastDt = 0
local ffi = require('ffi')

---------------------------------------------------------------- helpers

local function showMessage(text, seconds)
  message, messageTime = text, seconds or 4
  log('I', logTag, text)
end

local function loadSettings()
  local saved = jsonReadFile(SETTINGS_FILE)
  for k, v in pairs(defaults) do cfg[k] = v end
  if saved then
    local missing = false
    for k in pairs(defaults) do
      if saved[k] == nil then missing = true end
    end
    for k, v in pairs(saved) do
      if defaults[k] ~= nil then cfg[k] = v end
    end
    if missing then jsonWriteFile(SETTINGS_FILE, cfg, true) end -- add settings from newer versions
  else
    FS:directoryCreate(SETTINGS_DIR, true)
    jsonWriteFile(SETTINGS_FILE, cfg, true)
  end
end

local function levelOrigin(levelName, refZ)
  local levels = jsonReadFile(LEVELS_FILE) or {}
  if not levels[levelName] then
    local used = {}
    for _, o in pairs(levels) do used[o.slot or 0] = true end
    local slot = 1
    while used[slot] do slot = slot + 1 end
    levels[levelName] = {slot = slot, x = slot * LEVEL_SPACING, z = slot * LEVEL_SPACING, y = math.floor(refZ)}
    FS:directoryCreate(SETTINGS_DIR, true)
    jsonWriteFile(LEVELS_FILE, levels, true)
  end
  return levels[levelName]
end

local function toMc(p)
  return p.x + origin.x, p.z - origin.y + Y_BASE, origin.z - p.y
end

local function fromMc(x, y, z)
  return vec3(x - origin.x, origin.z - z, y - Y_BASE + origin.y)
end

-- Minecraft yaw/pitch (degrees) -> BeamNG direction
local function lookDir(yaw, pitch)
  local y, p = math.rad(yaw), math.rad(pitch)
  local cp = math.cos(p)
  return vec3(-math.sin(y) * cp, -math.cos(y) * cp, -math.sin(p))
end

local function yawFromDir(d)
  return math.deg(math.atan2(-d.x, -d.y))
end

local function blockKeyAt(p) -- Minecraft block key of a BeamNG position
  return math.floor(p.x + origin.x) .. ',' .. math.floor(p.z - origin.y + Y_BASE) .. ',' .. math.floor(origin.z - p.y)
end

-- A removed block whose collision BeamNG still has (until the next rebuild)
local function isGhostAt(p)
  local key = blockKeyAt(p)
  return collided[key] ~= nil and blocks[key] == nil
end

-- First surface below pos within depth, including the player's blocks but not removed ones.
local function groundBelow(pos, depth)
  local s = vec3(pos)
  local bottom = pos.z - depth
  for _ = 1, 32 do
    local len = s.z - bottom
    if len <= 0 then return nil end
    local d = castRayStatic(s, vec3(0, 0, -1), len)
    if d >= len then return nil end
    local hitZ = s.z - d
    local probe = vec3(s.x, s.y, hitZ - 0.05)
    if not origin or not isGhostAt(probe) then return hitZ end
    s.z = math.floor(probe.z - origin.y + Y_BASE) - Y_BASE + origin.y - 0.01 -- just below that block
  end
end

---------------------------------------------------------------- network

local function openSocket()
  if udp then return true end
  udp = socket.udp()
  if not udp:setsockname('127.0.0.1', cfg.bngPort) then
    log('E', logTag, 'Could not open UDP port ' .. cfg.bngPort .. ' - is another BeamNG running?')
    udp = nil
    return false
  end
  udp:settimeout(0)
  -- overlay frames arrive in bursts of many packets; a bigger buffer keeps them from being dropped
  pcall(udp.setoption, udp, 'rcvbuf', 8 * 1024 * 1024)
  log('I', logTag, 'Listening on 127.0.0.1:' .. cfg.bngPort)
  return true
end

local function flush()
  if not udp or #outLines == 0 then return end
  udp:sendto(table.concat(outLines, '\n') .. '\n', '127.0.0.1', cfg.mcPort)
  table.clear(outLines)
  outLen = 0
end

local function send(line)
  if outLen + #line > 7000 then flush() end
  outLines[#outLines + 1] = line
  outLen = outLen + #line + 1
end

-- Minecraft writes real block textures into our user folder, so it needs to know where that is
local function sendHello()
  send('userpath ' .. FS:getUserPath())
  send('hello')
end

local function connected()
  return now - lastHeard < 3
end

local function inWorld()
  return connected() and st ~= nil and now - lastSt < 2
end

---------------------------------------------------------------- blocks

-- Materials outside a level folder aren't loaded automatically; they must exist before a shape
-- using them is first loaded.
local function ensureMaterials()
  if scenetree.findObject('beamcraft_steve_skin') and scenetree.findObject('beamcraft_c11') then return end
  loadJsonMaterialsFile('/art/beamcraft/blocks/main.materials.json')
  loadJsonMaterialsFile('/art/beamcraft/steve/main.materials.json')
end

local blockSig = {}    -- key -> the block's description, to skip re-creating identical blocks
local decor = {}       -- key -> true: shown only, no collision (lever, redstone, torch, flower...)
local blockY = {}      -- key -> Minecraft y
local chunkBlocks = {} -- "cx,cz" -> {key = true}: blocks per Minecraft chunk
local chunkSeen = nil  -- while Minecraft lists a chunk: keys it mentioned

local function chunkKey(x, z) return math.floor(x / 16) .. ',' .. math.floor(z / 16) end

local function removeBlock(key)
  local o = blocks[key]
  if o then
    if not decor[key] then markCollisionDirty(o:getPosition()) end
    o:delete()
    blocks[key] = nil
  end
  blockSig[key] = nil
  blockY[key] = nil
  decor[key] = nil
  local x, z = key:match('^(-?%d+),%-?%d+,(-?%d+)$')
  local list = x and chunkBlocks[chunkKey(tonumber(x), tonumber(z))]
  if list then list[key] = nil end
end

-- "chunkend cx cz yMin yMax": Minecraft has listed every block in that area, so anything else
-- we still show there is stale (an invisible-in-Minecraft ghost) and goes.
local function finishChunk(a)
  local list = chunkBlocks[a[2] .. ',' .. a[3]]
  local yMin, yMax = tonumber(a[4]), tonumber(a[5])
  if list then
    local stale = {}
    for key in pairs(list) do
      local y = blockY[key]
      if y and y >= yMin and y <= yMax and not (chunkSeen and chunkSeen[key]) then stale[#stale + 1] = key end
    end
    for _, key in ipairs(stale) do removeBlock(key) end
  end
  chunkSeen = nil
end

local function clearBlocks()
  for key in pairs(blocks) do removeBlock(key) end
  table.clear(blockSig)
  table.clear(blockY)
  table.clear(decor)
  table.clear(chunkBlocks)
end

local function setBlock(a)
  local x, y, z = tonumber(a[2]), tonumber(a[3]), tonumber(a[4])
  local key = x .. ',' .. y .. ',' .. z
  if chunkSeen then chunkSeen[key] = true end
  local sig = table.concat(a, ' ', 5)
  if blocks[key] and blockSig[key] == sig then return end -- already showing exactly this block
  removeBlock(key)
  local color = tonumber(a[5])
  if color < 0 or not origin then return end
  local minx, miny, minz = tonumber(a[6]), tonumber(a[7]), tonumber(a[8])
  local maxx, maxy, maxz = tonumber(a[9]), tonumber(a[10]), tonumber(a[11])
  -- the box's min corner in BeamNG space (Minecraft +z is BeamNG -y)
  local pos = vec3(x + minx - origin.x, origin.z - z - maxz, y + miny - Y_BASE + origin.y)
  if math.abs(pos.x) > LEVEL_SPACING / 2 or math.abs(pos.y) > LEVEL_SPACING / 2 then return end -- another map's block
  ensureMaterials()
  -- real Minecraft textures (exported by Minecraft into our user folder), else a plain colour cube
  local shapeName = '/art/beamcraft/blocks/c' .. color .. '.dae'
  local shape = a[12]
  if shape and FS:fileExists('/art/beamcraft/gen/' .. shape .. '.dae') then
    for i = 13, #a do -- every texture the block's model uses
      local tex = a[i]
      if tex and not loadedTextures[tex] then
        loadedTextures[tex] = true
        local matFile = '/art/beamcraft/tex/' .. tex .. '.materials.json'
        if FS:fileExists(matFile) then loadJsonMaterialsFile(matFile) end
      end
    end
    shapeName = '/art/beamcraft/gen/' .. shape .. '.dae'
  end
  local solid = a[1] ~= 'blkd'
  local belowKey = x .. ',' .. (y - 1) .. ',' .. z
  if not solid and not (blocks[belowKey] and not decor[belowKey]) then
    -- resting on the invisible ground, which is BeamNG's ground rounded to whole blocks: put
    -- flat things (redstone, rails, plates...) on the real surface so they aren't buried or floating
    local cellBottom = y - Y_BASE + origin.y
    local g = groundBelow(vec3(x + 0.5 - origin.x, origin.z - z - 0.5, cellBottom + 0.6), 1.2)
    if g and math.abs(g - cellBottom) <= 0.55 then pos.z = pos.z + (g - cellBottom) end
  end
  local o = createObject('TSStatic')
  o:setField('shapeName', 0, shapeName)
  o:setField('collisionType', 0, solid and 'Visible Mesh Final' or 'None')
  o:setField('decalType', 0, solid and 'Visible Mesh Final' or 'None')
  o:setField('allowPlayerStep', 0, '1')
  o:setPosition(pos)
  o.scale = vec3(math.max(maxx - minx, 0.01), math.max(maxz - minz, 0.01), math.max(maxy - miny, 0.01))
  o.canSave = false
  o:registerObject('')
  blocks[key] = o
  blockSig[key] = sig
  decor[key] = (not solid) or nil
  blockY[key] = y
  local ck = chunkKey(x, z)
  chunkBlocks[ck] = chunkBlocks[ck] or {}
  chunkBlocks[ck][key] = true
  if solid then markCollisionDirty(pos) end
end

---------------------------------------------------------------- Steve model

local function ensureSteve()
  if steve then return end
  ensureMaterials()
  steve = createObject('TSStatic')
  steve:setField('shapeName', 0, '/art/beamcraft/steve/steve.dae')
  steve:setField('collisionType', 0, 'None')
  steve:setField('decalType', 0, 'None')
  steve.canSave = false
  steve:registerObject('')
  steve.hidden = true
end

local function deleteSteve()
  if steve then steve:delete() end
  steve = nil
end

---------------------------------------------------------------- terrain mirroring

-- The player's own Minecraft blocks are handled by Minecraft itself, so every ray here looks
-- through them (otherwise a house becomes invisible walls around the house). That includes blocks
-- broken since BeamNG's collision was last rebuilt: BeamNG still has their collision.
local function isOurBlockAt(p)
  local key = blockKeyAt(p)
  return (blocks[key] ~= nil and not decor[key]) or collided[key] ~= nil
end

local DOWN, UP = vec3(0, 0, -1), vec3(0, 0, 1)
local SIDES = {vec3(1, 0, 0), vec3(-1, 0, 0), vec3(0, 1, 0), vec3(0, -1, 0)}

-- Height of the first map surface below `start` (BeamNG z), skipping our own blocks.
local function rayDown(start)
  local s = vec3(start)
  for _ = 1, 64 do
    local d = castRayStatic(s, DOWN, 300)
    if d >= 300 then return nil end
    local hitZ = s.z - d
    local probe = vec3(s.x, s.y, hitZ - 0.05)
    if not isOurBlockAt(probe) then return hitZ end
    s.z = math.floor(probe.z - origin.y + Y_BASE) - Y_BASE + origin.y - 0.01 -- just below that block
  end
end

-- Height of the first map geometry between z0 and z1 straight up from (x, y), or nil.
local function rayUp(x, y, z0, z1)
  local s = vec3(x, y, z0)
  for _ = 1, 16 do
    local len = z1 - s.z
    if len <= 0 then return nil end
    local d = castRayStatic(s, UP, len)
    if d >= len then return nil end
    local p = vec3(x, y, s.z + d + 0.05)
    if not isOurBlockAt(p) then return s.z + d end
    s.z = math.floor(p.z - origin.y + Y_BASE) + 1 - Y_BASE + origin.y + 0.01 -- just above that block
  end
end

local function blockedUp(x, y, z0, z1) return rayUp(x, y, z0, z1) ~= nil end

-- Does a wall / pillar / railing pass through the cell around (x, y) at height z?
local function wallInColumn(x, y, z, half)
  half = half or 0.5
  local c = vec3(x, y, z)
  for _, dir in ipairs(SIDES) do
    local d = castRayStatic(c, dir, half)
    if d < half and not isOurBlockAt(c + dir * (d + 0.05)) then return true end
  end
  return false
end

local function mcTop(z) return math.floor(z - origin.y + Y_BASE + 0.5) end

-- BeamNG's water (seas are WaterPlanes, lakes/ponds WaterBlocks), collected when a map loads
local waterPlanes, waterBlocks = {}, {}

local function collectWater()
  table.clear(waterPlanes)
  table.clear(waterBlocks)
  for _, name in ipairs(scenetree.findClassObjects('WaterPlane') or {}) do
    local o = scenetree.findObject(name)
    if o then waterPlanes[#waterPlanes + 1] = o:getPosition().z end
  end
  for _, name in ipairs(scenetree.findClassObjects('WaterBlock') or {}) do
    local o = scenetree.findObject(name)
    if o then
      local b = o:getWorldBox()
      local mn, mx = b.minExtents, b.maxExtents
      waterBlocks[#waterBlocks + 1] = {mn.x, mn.y, mx.x, mx.y, mx.z}
    end
  end
  log('I', logTag, string.format('water on this map: %d sea plane(s), %d lake block(s)', #waterPlanes, #waterBlocks))
end

-- Height of BeamNG's water surface over (x, y), or nil
local function waterSurfaceAt(x, y)
  local best
  for _, z in ipairs(waterPlanes) do
    if not best or z > best then best = z end
  end
  for _, b in ipairs(waterBlocks) do
    if x >= b[1] and x <= b[3] and y >= b[2] and y <= b[4] and (not best or b[5] > best) then best = b[5] end
  end
  return best
end

local NO_WATER = -100000

-- First air block above the ground in the Minecraft column (mcx, mcz), given Steve's feet at
-- BeamNG height feetZ, plus the first block above the water surface if this spot is under open
-- water (so boats float and Steve swims). These barrier blocks are only the ground to place blocks
-- on (and for dropped items); Steve's own collisions use BeamNG's real geometry. So walls, pillars
-- and roofs are left out on purpose: as invisible blocks they would only get in the way of placing.
local function columnTop(mcx, mcz, feetZ)
  local x, y = mcx + 0.5 - origin.x, origin.z - (mcz + 0.5)
  local floor = rayDown(vec3(x, y, feetZ + 2.2)) -- just above head height, so roofs don't count
  if not floor then return nil end
  local waterTop = NO_WATER
  local surface = waterSurfaceAt(x, y)
  if surface and surface > floor + 0.05 then
    -- only open water: nothing between the surface and the floor (no tunnel below sea level)
    local fromAbove = rayDown(vec3(x, y, surface + 3))
    if fromAbove and math.abs(fromAbove - floor) < 0.2 then waterTop = mcTop(surface) end
  end
  return mcTop(floor), waterTop
end

---------------------------------------------------------------- BeamNG geometry for Steve's collisions
-- Steve's movement collides with BeamNG's real geometry, sampled on a fine grid around him: each
-- cell gets a box whose top is the exact floor height, walls/pillars become tall boxes, and low
-- undersides (bridges, door frames) get a ceiling box. Minecraft uses these instead of the 1 m
-- barrier blocks, which only remain for placing blocks and for dropped items.

local geoSent = {}              -- cell id -> what Minecraft currently has for it
local geoQueue, geoQueueTimer, geoQueuePos = {}, 0, 1
local geoFeetBucket

local function mcBox(x0, y0, z0, x1, y1, z1) -- BeamNG box -> "x0 y0 z0 x1 y1 z1" in Minecraft space
  return string.format('%.3f %.3f %.3f %.3f %.3f %.3f',
    x0 + origin.x, z0 - origin.y + Y_BASE, origin.z - y1,
    x1 + origin.x, z1 - origin.y + Y_BASE, origin.z - y0)
end

local EMPTY_BOX = '0 0 0 0 0 0'

-- {id = box} for grid cell (i, j), seen from feet height feetZ
local function geoCell(i, j, feetZ)
  local S = cfg.geoCell
  local x0, y0 = i * S, j * S
  local x, y = x0 + S / 2, y0 + S / 2
  local id = i .. ':' .. j
  local out = {[id] = EMPTY_BOX, [id .. 'c'] = EMPTY_BOX}
  local floor = rayDown(vec3(x, y, feetZ + 2.2))
  if not floor then return out end
  local high = rayDown(vec3(x, y, feetZ + cfg.rayStartHeight))
  if high and high > floor + 0.3 then
    if wallInColumn(x, y, floor + 1.0, S / 2) or blockedUp(x, y, floor + 0.2, floor + 1.85) then
      out[id] = mcBox(x0, y0, floor - 1, x0 + S, y0 + S, high) -- wall / pillar / too low to stand
      return out
    end
    local under = rayUp(x, y, floor + 1.85, floor + 4) -- something you could bump your head on
    if under then out[id .. 'c'] = mcBox(x0, y0, under, x0 + S, y0 + S, under + 0.3) end
  end
  out[id] = mcBox(x0, y0, floor - 1, x0 + S, y0 + S, floor)
  return out
end

local function geoClear()
  table.clear(geoSent)
  table.clear(geoQueue)
  geoQueuePos = 1
  geoQueueTimer = 0
  geoFeetBucket = nil
  send('geoclear')
end

-- Sample up to `budget` queued cells around `feet` and send what changed.
local function geoUpdate(feet, dt, budget)
  local S = cfg.geoCell
  local bucket = math.floor(feet.z / 0.5)
  geoQueueTimer = geoQueueTimer - dt
  if geoQueueTimer <= 0 or bucket ~= geoFeetBucket then
    -- (re)build the list of cells around Steve, nearest first
    geoQueueTimer = 0.3
    if bucket ~= geoFeetBucket then table.clear(geoSent) end -- other floor height: re-check everything
    geoFeetBucket = bucket
    local ci, cj = math.floor(feet.x / S), math.floor(feet.y / S)
    local r = math.ceil(cfg.geoRadius / S)
    table.clear(geoQueue)
    for di = -r, r do
      for dj = -r, r do
        local d2 = di * di + dj * dj
        if d2 <= r * r and geoSent[(ci + di) .. ':' .. (cj + dj)] == nil then
          geoQueue[#geoQueue + 1] = {ci + di, cj + dj, d2}
        end
      end
    end
    table.sort(geoQueue, function(p, q) return p[3] < q[3] end)
    geoQueuePos = 1
    -- forget what Minecraft has dropped (it keeps 12 m around Steve)
    for id in pairs(geoSent) do
      local i, j = id:match('^(-?%d+):(-?%d+)')
      if i and (math.abs(tonumber(i) - ci) * S > 11 or math.abs(tonumber(j) - cj) * S > 11) then geoSent[id] = nil end
    end
  end
  local parts, n = {}, 0
  local function flushGeo()
    if n > 0 then send('geo ' .. n .. ' ' .. table.concat(parts, ' ')) end
    parts, n = {}, 0
  end
  while budget > 0 and geoQueuePos <= #geoQueue do
    local c = geoQueue[geoQueuePos]
    geoQueuePos = geoQueuePos + 1
    budget = budget - 1
    for id, box in pairs(geoCell(c[1], c[2], feet.z)) do
      local had = geoSent[id]
      if had ~= box then
        geoSent[id] = box
        -- an empty box only needs sending if Minecraft had something there
        if box ~= EMPTY_BOX or (had ~= nil and had ~= EMPTY_BOX) then
          parts[#parts + 1] = id .. ' ' .. box
          n = n + 1
          if n >= 40 then flushGeo() end
        end
      end
    end
  end
  flushGeo()
end

-- Queue every column around a BeamNG position.
local function queueTerrain(center, radius)
  local cx, _, cz = toMc(center)
  cx, cz = math.floor(cx), math.floor(cz)
  terrainCenter = {x = cx, z = cz, feetZ = center.z}
  table.clear(terrainQueue)
  for dx = -radius, radius do
    for dz = -radius, radius do
      if dx * dx + dz * dz <= radius * radius + 1 then
        terrainQueue[#terrainQueue + 1] = {cx + dx, cz + dz}
      end
    end
  end
  table.sort(terrainQueue, function(a, b)
    return (a[1] - cx) ^ 2 + (a[2] - cz) ^ 2 < (b[1] - cx) ^ 2 + (b[2] - cz) ^ 2
  end)
end

-- Raycast and send up to `budget` queued columns.
local function processTerrain(budget)
  if not terrainCenter or #terrainQueue == 0 then return end
  local parts, count = {'terrainw'}, 0
  while #terrainQueue > 0 and budget > 0 do
    local c = table.remove(terrainQueue, 1)
    local top, waterTop = columnTop(c[1], c[2], terrainCenter.feetZ)
    if top then
      parts[#parts + 1] = c[1] .. ' ' .. c[2] .. ' ' .. top .. ' ' .. (waterTop or NO_WATER)
      count = count + 1
      if count >= 150 then
        send(table.concat(parts, ' '))
        parts, count = {'terrainw'}, 0
      end
    end
    budget = budget - 1
  end
  if count > 0 then send(table.concat(parts, ' ')) end
end

---------------------------------------------------------------- on foot

local function setMouseLock(lock)
  if lock == mouseLocked then return end
  mouseLocked = lock
  lockMouse(lock)
  local canvas = scenetree.findObject('Canvas')
  if canvas then canvas:setCursorVisible(not lock) end
end

-- Minecraft positions arrive in bursts (its game loop runs unevenly while hidden), so each one is
-- stamped with its tick number and replayed on our own smooth clock, a couple of ticks behind.
local PLAYBACK_DELAY = 2 -- ticks (0.1 s)

local function advanceClock(dt)
  local n = #snaps
  if n == 0 then return end
  local target = snaps[n].tick - PLAYBACK_DELAY
  if not renderTick or math.abs(renderTick - target) > 8 then
    renderTick = target
  else
    renderTick = renderTick + dt * 20 + (target - renderTick) * math.min(dt * 2, 1)
  end
end

local function stevePos()
  if not st then return nil end
  local x, y, z = st.x, st.y, st.z
  local n = #snaps
  if n > 0 and renderTick then
    local a, b = snaps[1], snaps[n]
    if renderTick <= a.tick then
      x, y, z = a.x, a.y, a.z
    elseif renderTick >= b.tick then
      x, y, z = b.x, b.y, b.z
    else
      for i = n - 1, 1, -1 do
        if snaps[i].tick <= renderTick then
          a, b = snaps[i], snaps[i + 1]
          break
        end
      end
      local f = (renderTick - a.tick) / math.max(b.tick - a.tick, 1e-6)
      x, y, z = a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f
    end
  end
  local p = fromMc(x, y, z)
  p.z = p.z + visualZOffset
  return p
end

local function unicycle()
  return gameplay_walk and gameplay_walk.getCurrentUnicycle and gameplay_walk.getCurrentUnicycle()
end

local function teleportSteve(pos, yaw)
  queueTerrain(pos, cfg.terrainRadius)
  processTerrain(10000)
  local x, y, z = toMc(pos)
  -- BeamNG's real geometry around the landing spot first, so Steve has ground under him at once
  geoClear()
  geoUpdate(pos, 0, 250)
  send(string.format('tp %.3f %.3f %.3f %.2f', x, y + 0.05, z, yaw))
  st = {x = x, y = y, z = z, yaw = yaw, pitch = 0, eye = 1.62, onGround = true, health = st and st.health or 20,
        maxHealth = st and st.maxHealth or 20, food = st and st.food or 20, slot = st and st.slot or 0,
        gm = st and st.gm or 0, persp = st and st.persp or 0, reach = st and st.reach or 3, xp = st and st.xp or 0}
  -- until Minecraft reports Steve at the new spot, ignore older positions so the camera doesn't jump back
  table.clear(snaps)
  renderTick = nil
  tpGuard = {x = x, y = y, z = z, untilTime = now + 2}
  safePos = pos
end

-- Where Steve should appear when getting out: beside the driver's door of the car he just left
-- (BeamNG's own walking body can still be somewhere else for a moment, e.g. after a Tab switch).
local function exitPosition(uni)
  local car = lastCarId and getObjectByID(lastCarId)
  local p
  if car and gameplay_walk and gameplay_walk.getDoorsidePosRot then
    local ok, doorPos = pcall(gameplay_walk.getDoorsidePosRot, car)
    if ok and doorPos then p = vec3(doorPos) end
  end
  p = p or uni:getPosition()
  -- stand on whatever is there - the map or the player's own blocks (a car parked on a block
  -- floor must not drop him underneath it) - searching well above and below
  local ground = groundBelow(vec3(p.x, p.y, p.z + 2.2), 30) or groundBelow(vec3(p.x, p.y, p.z + 20), 60)
  return vec3(p.x, p.y, ground or p.z)
end

local function startOnFoot()
  local uni = unicycle()
  if not uni then return end
  local pos = exitPosition(uni)
  local camDir = core_camera.getForward()
  look.yaw = yawFromDir(camDir)
  look.pitch = 0
  teleportSteve(pos, look.yaw)
  onFoot = true
  keys = 0
  -- Steve himself (F5), the hand and held items are drawn by Minecraft into the overlay
  uni:queueLuaCommand("local c = controller.getControllerSafe('playerController') if c and c.setFreeze then c.setFreeze(1) end")
  core_camera.setGlobalCameraByName('beamcraft')
  pushActionMapHighestPriority('BeamCraft')
  showMessage('BeamCraft: you are Steve. F = get in a car, E = inventory, T = chat', 5)
  -- BeamNG's E opens its radial menu; on foot E is the Minecraft inventory instead
  if core_quickAccess and core_quickAccess.toggle and not quickAccessToggle then
    quickAccessToggle = core_quickAccess.toggle
    core_quickAccess.toggle = function(...)
      if onFoot then return end
      return quickAccessToggle(...)
    end
  end
  -- BeamNG left uncapped keeps the GPU ~90% busy, and Minecraft's overlay frames then wait
  -- a quarter of a second for their turn. Cap BeamNG while on foot; the player's own setting comes back after.
  applyFpsLimit(cfg.footFpsLimit)
  guihooks.trigger('appContainer:loadLayoutByType', 'beamcraft')
  layoutRetry = 0.5 -- the walking camera may switch the layout right after us; switch back once more
  lastSizeSent = nil
end

local function stopOnFoot()
  onFoot = false
  keys = 0
  target = nil
  popActionMap('BeamCraft')
  if core_camera.getActiveGlobalCameraName() == 'beamcraft' then core_camera.setGlobalCameraByName(nil) end
  setMouseLock(false)
  if steve then steve.hidden = true end
  layoutRetry = nil
  restoreFpsLimit()
  core_gamestate.requestGameState() -- back to the normal driving HUD layout
  local uni = unicycle()
  if uni then
    uni.hidden = false
    uni:queueLuaCommand("local c = controller.getControllerSafe('playerController') if c and c.setFreeze then c.setFreeze(0) end")
  end
end

-- Which car (and which point on it) is under the crosshair, within Minecraft reach.
local function findCarTarget(eye, dir, reach)
  local bestT, best
  local rayEnd = eye + dir * reach
  for _, veh in ipairs(getAllVehicles()) do
    if veh:getActive() and veh:getJBeamFilename() ~= 'unicycle' then
      local box = veh:getWorldBox()
      local bc, be_ = box:getCenter(), box:getExtents()
      local c = vec3(bc.x, bc.y, bc.z)
      local radius = math.sqrt(be_.x * be_.x + be_.y * be_.y + be_.z * be_.z) / 2
      if c:distanceToLineSegment(eye, rayEnd) < radius then
        local vpos = veh:getPosition()
        for i = 0, veh:getNodeCount() - 1 do
          local p = vpos + veh:getNodePosition(i)
          local rel = p - eye
          local t = rel:dot(dir)
          if t > 0 and t < reach and (not bestT or t < bestT) then
            if (rel - dir * t):length() < 0.35 then
              bestT = t
              best = {veh = veh, point = eye + dir * t, dir = dir, dist = t}
            end
          end
        end
      end
    end
  end
  if best and castRayStatic(eye, dir, bestT) < bestT then return nil end -- a wall is in the way
  return best
end

local function damageCar(damage, crit)
  if not target or not target.veh or not scenetree.findObjectById(target.veh:getID()) then
    log('I', logTag, string.format('Minecraft hit (%.1f damage) but no car is under the crosshair any more', damage))
    return
  end
  local impulse = cfg.hitImpulse * damage
  log('I', logTag, string.format('hit car %d: %.1f damage, %.0f N*s', target.veh:getID(), damage, impulse))
  local hp, d = target.point, target.dir
  target.veh:queueLuaCommand(string.format([[
    local hp, dir, r, J = vec3(%f, %f, %f), vec3(%f, %f, %f), %f, %f
    local base = obj:getPosition()
    local sel, total = {}, 0
    for _, n in pairs(v.data.nodes) do
      local dist = (base + obj:getNodePosition(n.cid)):distance(hp)
      if dist < r then
        local w = 1 - dist / r
        sel[#sel + 1] = n.cid
        sel[#sel + 1] = w
        total = total + w
      end
    end
    if total > 0 then
      local t = 0.01
      for i = 1, #sel, 2 do obj:applyForceVectorTime(sel[i], dir * (J * sel[i + 1] / total / t), t) end
    end
  ]], hp.x, hp.y, hp.z, d.x, d.y, d.z, cfg.hitRadius, impulse))
  if crit then showMessage(string.format('Critical hit! %.1f damage', damage), 1.5) end
end

-- A Minecraft explosion (TNT etc.): push every node of nearby cars away from the blast.
local function explode(a)
  if not origin then return end
  local c = fromMc(tonumber(a[2]), tonumber(a[3]), tonumber(a[4]))
  local power = tonumber(a[5]) or 4
  local radius = power * 2
  local strength = cfg.explosionStrength * power
  local count = 0
  for _, veh in ipairs(getAllVehicles()) do
    if veh:getActive() and veh:getJBeamFilename() ~= 'unicycle' and veh:getPosition():distance(c) < radius + 6 then
      count = count + 1
      veh:queueLuaCommand(string.format([[
        local c, R, J = vec3(%f, %f, %f), %f, %f
        local base = obj:getPosition()
        local t = 0.01
        for _, n in pairs(v.data.nodes) do
          local p = base + obj:getNodePosition(n.cid)
          local d = p:distance(c)
          if d < R then
            local dir = (p - c):normalized()
            obj:applyForceVectorTime(n.cid, dir * (J * (1 - d / R) / t), t)
          end
        end
      ]], c.x, c.y, c.z, radius, strength))
    end
  end
  log('I', logTag, string.format('explosion (power %.1f) hit %d car(s)', power, count))
end

---------------------------------------------------------------- messages from Minecraft

local function onStatus(a)
  local s = {
    x = tonumber(a[2]), y = tonumber(a[3]), z = tonumber(a[4]),
    yaw = tonumber(a[5]), pitch = tonumber(a[6]), eye = tonumber(a[7]),
    onGround = a[8] == '1', health = tonumber(a[9]), maxHealth = tonumber(a[10]),
    food = tonumber(a[11]), slot = tonumber(a[12]), gm = tonumber(a[13]),
    persp = tonumber(a[14]), sneak = a[15] == '1', cooldown = tonumber(a[16]),
    reach = tonumber(a[17]), xp = tonumber(a[18]),
  }
  screenOpen = a[19] ~= '0'
  chatOpen = a[19] == '2'
  screenTyping = a[19] == '3'
  s.tick = tonumber(a[20]) or 0
  s.persp = s.persp or 0
  lastSt = now

  if tpGuard then
    local far = math.abs(s.x - tpGuard.x) + math.abs(s.y - tpGuard.y) + math.abs(s.z - tpGuard.z) > 6
    if far and now < tpGuard.untilTime then
      -- still the old position from before the teleport: keep showing the teleport target
      s.x, s.y, s.z = tpGuard.x, tpGuard.y, tpGuard.z
      st, stTime = s, now
      return
    end
    tpGuard = nil
  end
  st, stTime = s, now
  local last = snaps[#snaps]
  if last and (s.tick <= last.tick - 40 or math.abs(last.x - s.x) + math.abs(last.z - s.z) > 64) then
    table.clear(snaps) -- respawned / teleported / rejoined: start over, no sliding
    renderTick = nil
    last = nil
  end
  if not last or s.tick > last.tick then
    snaps[#snaps + 1] = {tick = s.tick, x = s.x, y = s.y, z = s.z}
    if #snaps > 30 then table.remove(snaps, 1) end
  end
  if mcStatus ~= 'world' then
    mcStatus = 'world'
    showMessage('BeamCraft: Minecraft is connected', 3)
  end
end

local function handleLine(line)
  local a = {}
  for w in line:gmatch('%S+') do a[#a + 1] = w end
  local cmd = a[1]
  if cmd == 'st' then
    onStatus(a)
  elseif cmd == 'hb' then
    for i = 1, 9 do
      local item = a[i + 1]
      if item == nil or item == '-' then hotbar[i] = nil
      else
        local name, count = item:match('^(.-):(%d+)$')
        hotbar[i] = {name = name or item, count = tonumber(count) or 1}
      end
    end
  elseif cmd == 'hud' then
    -- hud <frame> <part> <parts> <x> <y> <w> <h> <fullW> <fullH> <base64>: one PNG (only the part
    -- of Minecraft's frame that has something in it), split across datagrams. Frames are encoded
    -- in parallel, so parts of different frames can interleave; only ever show newer frames.
    local frameNo, part, parts = tonumber(a[2]), tonumber(a[3]), tonumber(a[4])
    if frameNo <= hudShownFrame then return end
    local f = hudPending[frameNo]
    if not f then
      f = {parts = {}, count = 0}
      hudPending[frameNo] = f
    end
    if not f.parts[part + 1] then
      f.parts[part + 1] = a[11] or ''
      f.count = f.count + 1
    end
    if f.count == parts then
      for n in pairs(hudPending) do
        if n <= frameNo then hudPending[n] = nil end
      end
      hudShownFrame = frameNo
      hudFramesReceived = hudFramesReceived + 1
      local data = table.concat(f.parts)
      lastHudImage = {
        img = data ~= '-' and ('data:image/png;base64,' .. data) or nil,
        x = tonumber(a[5]), y = tonumber(a[6]), w = tonumber(a[7]), h = tonumber(a[8]),
        fw = tonumber(a[9]), fh = tonumber(a[10]),
      }
      if onFoot then guihooks.trigger('BeamCraftHudFrame', lastHudImage) end
    end
  elseif cmd == 'blk' or cmd == 'blkd' then
    setBlock(a)
  elseif cmd == 'chunk' then
    chunkSeen = {}
  elseif cmd == 'chunkend' then
    finishChunk(a)
  elseif cmd == 'blkclear' then
    clearBlocks()
  elseif cmd == 'boom' then
    explode(a)
  elseif cmd == 'hit' then
    damageCar(tonumber(a[2]) or 1, a[3] == '1')
  elseif cmd == 'respawned' then
    if onFoot and safePos then teleportSteve(safePos, look.yaw) end
    showMessage('You died!', 3)
  elseif cmd == 'status' then
    mcStatus = a[2] or 'menu'
  elseif cmd == 'hello' then
    -- handshake answer: Minecraft may have restarted, so its frame numbers start over
    hudShownFrame, hudPending = -1, {}
  end
end

local function receive()
  if not udp then return end
  for _ = 1, 500 do
    local data, err = udp:receive()
    if not data then
      if err == 'timeout' then return end
      -- "connection refused" just means Minecraft isn't listening yet; keep draining
    else
      lastHeard = now
      for line in data:gmatch('[^\n]+') do
        local ok, e = pcall(handleLine, line)
        if not ok then log('W', logTag, 'Bad message "' .. line .. '": ' .. tostring(e)) end
      end
    end
  end
end

-- Overlay frames come over TCP (127.0.0.1:47822): menus are 100+ KB per frame, and over UDP most
-- of each burst was dropped before we got to read it, which froze the inventory.
local HUD_TCP_PORT = 47822
local tcp, tcpBuf, tcpRetry = nil, '', 0

local function closeTcp()
  if tcp then tcp:close() end
  tcp, tcpBuf = nil, ''
end

local function receiveTcp(dt)
  if not tcp then
    tcpRetry = tcpRetry - dt
    if tcpRetry > 0 or not connected() then return end
    tcpRetry = 2
    local t = socket.tcp()
    t:settimeout(0.05)
    if not t:connect('127.0.0.1', HUD_TCP_PORT) then
      t:close()
      return
    end
    t:settimeout(0)
    tcp, tcpBuf = t, ''
    log('I', logTag, 'Overlay channel connected')
  end
  local parts = {tcpBuf}
  for _ = 1, 256 do -- up to 16 MB per frame
    local data, err, partial = tcp:receive(65536)
    local chunk = data or partial
    if chunk and #chunk > 0 then parts[#parts + 1] = chunk end
    if err == 'closed' then
      closeTcp()
      return
    end
    if not data then break end
  end
  local buf = table.concat(parts)
  local rest = 1
  for line, nextPos in buf:gmatch('([^\n]*)\n()') do
    if #line > 0 then
      local ok, e = pcall(handleLine, line)
      if not ok then log('W', logTag, 'Bad overlay message: ' .. tostring(e)) end
    end
    rest = nextPos
  end
  tcpBuf = buf:sub(rest)
end

---------------------------------------------------------------- input (called from the BeamCraft action map)

local keyBits = {fwd = 1, back = 2, left = 4, right = 8, jump = 16, sneak = 32, sprint = 64, attack = 128, use = 256}

function M.onKey(name, value)
  local b = keyBits[name]
  if not b then return end
  if chatBuf and value > 0.5 then return end -- typing in chat
  if value > 0.5 then
    if band(keys, b) == 0 and (name == 'attack' or name == 'use') then send('press ' .. name) end
    keys = bor(keys, b)
  else
    keys = band(keys, bnot(b))
  end
end

local lastPerspectivePress = -1

function M.onPress(name, arg)
  if not onFoot or chatBuf then return end -- keys are for typing while chat is open
  if name == 'perspective' then
    -- F5 can arrive twice (our binding and BeamNG's own F5, which we redirect); count it once
    if now - lastPerspectivePress < 0.2 then return end
    lastPerspectivePress = now
  end
  if name == 'inventory' then
    -- the inventory is drawn by Minecraft into the overlay; E opens it and E closes it again
    keys = 0
    send(screenOpen and 'press close' or 'press inventory')
  elseif name == 'chat' or name == 'command' then
    -- Minecraft's chat is drawn in the overlay; the typing happens in a hidden text box here
    keys = 0
    send('press ' .. name)
    chatPending = 1
    chatBuf = im.ArrayChar(257, name == 'command' and '/' or '')
    chatLastText = name == 'command' and '/' or ''
    chatFocus = true
  elseif name == 'slot' then
    send('press slot ' .. tostring(arg))
  else
    send('press ' .. name)
  end
end

-- Esc on foot: closes an open Minecraft screen (inventory, chest, chat...) like in Minecraft;
-- with nothing open it does what Esc always does in BeamNG (its menu).
local escClosedScreenAt = -10
local escForwarded = false

function M.onEscape(value)
  if value > 0.5 then
    if onFoot and (screenOpen or chatOpen) and not menuOpen then
      send('press close')
      escClosedScreenAt = now
      escForwarded = false
      return
    end
    escForwarded = true
    guihooks.trigger('UINavigation', 'menu', value)
  elseif escForwarded then
    escForwarded = false
    guihooks.trigger('UINavigation', 'menu', value)
  end
end

function M.onScroll(value)
  if not onFoot or value == 0 or screenOpen then return end
  send('press scroll ' .. (value > 0 and '1' or '-1'))
end

---------------------------------------------------------------- camera (called by core/cameraModes/beamcraft.lua)

function M.updateCamera(data)
  if not onFoot or not st then return false end
  if not screenOpen and not menuOpen then
    local k = cfg.lookSensitivity
    local dt = data.dt or 0
    look.yaw = look.yaw + MoveManager.yawRelative * k + (MoveManager.yawRight - MoveManager.yawLeft) * 120 * dt
    local dp = MoveManager.pitchRelative * k + (MoveManager.pitchUp - MoveManager.pitchDown) * 120 * dt
    if cfg.invertY then dp = -dp end
    look.pitch = clamp(look.pitch - dp, -90, 90)
    look.yaw = (look.yaw + 180) % 360 - 180
  end
  local feet = stevePos()
  local eye = feet + vec3(0, 0, st.eye or 1.62)
  local dir = lookDir(look.yaw, look.pitch)
  local camPos, camDir = eye, dir
  if st.persp == 1 or st.persp == 2 then
    local back = st.persp == 1 and -dir or dir
    local dist = cfg.thirdPersonDistance
    local hit = castRayStatic(eye, back, dist)
    camPos = eye + back * math.max(math.min(hit, dist) - 0.2, 0.3)
    camDir = st.persp == 1 and dir or -dir
  end
  data.res.pos:set(camPos)
  data.res.rot = quatFromDir(camDir, vec3(0, 0, 1))
  data.res.fov = cfg.fov
  return true
end

---------------------------------------------------------------- HUD

local function col(r, g, b, a) return im.GetColorU322(im.ImVec4(r, g, b, a or 1)) end

local function drawHudContents(vp, dl)
  local W, H = vp.Size.x, vp.Size.y
  local ox, oy = vp.Pos.x, vp.Pos.y

  if not inWorld() then
    local text = mcStatus == 'menu' and 'BeamCraft: Minecraft is on the title screen - open the "BeamCraft" world'
      or mcStatus == 'loading' and 'BeamCraft: Minecraft is loading the world...'
      or 'BeamCraft: waiting for Minecraft (start the BeamCraft instance in Prism Launcher)'
    im.ImDrawList_AddRectFilled(dl, im.ImVec2(ox + 10, oy + 10), im.ImVec2(ox + 20 + #text * 7, oy + 34), col(0, 0, 0, 0.6))
    im.ImDrawList_AddText1(dl, im.ImVec2(ox + 15, oy + 14), col(1, 1, 0.6), text, nil)
  end

  if message and messageTime > 0 then
    im.ImDrawList_AddRectFilled(dl, im.ImVec2(ox + W / 2 - #message * 3.6 - 8, oy + 60), im.ImVec2(ox + W / 2 + #message * 3.6 + 8, oy + 84), col(0, 0, 0, 0.55))
    im.ImDrawList_AddText1(dl, im.ImVec2(ox + W / 2 - #message * 3.6, oy + 64), col(1, 1, 1), message, nil)
  end

  if not onFoot or not st or screenOpen then return end

  -- Minecraft draws the crosshair, hotbar, hearts and the red "car in reach" corners itself.
end

local hudFlags = bit.bor(im.WindowFlags_NoTitleBar, im.WindowFlags_NoResize, im.WindowFlags_NoMove,
  im.WindowFlags_NoInputs, im.WindowFlags_NoScrollbar, im.WindowFlags_NoScrollWithMouse,
  im.WindowFlags_NoSavedSettings, im.WindowFlags_NoFocusOnAppearing, im.WindowFlags_NoBringToFrontOnFocus,
  im.WindowFlags_NoBackground)
local transparent = im.GetColorU322(im.ImVec4(0, 0, 0, 0))

-- While typing in chat, BeamNG's own keys (F = car, Esc = menu, ...) must not fire.
local gameActionMaps = {'FirstActionMap', 'NormalActionMap', 'VehicleCommonActionMap', 'VehicleSpecificActionMap', 'BeamCraftActionMap'}

local function setGameKeysEnabled(enabled)
  if not enabled and not disabledMaps then
    disabledMaps = {}
    for _, name in ipairs(gameActionMaps) do
      local am = scenetree.findObject(name)
      if am then
        am:setEnabled(false)
        disabledMaps[#disabledMaps + 1] = am
      end
    end
  elseif enabled and disabledMaps then
    for _, am in ipairs(disabledMaps) do am:setEnabled(true) end
    disabledMaps = nil
  end
end

local function closeChatInput()
  chatBuf, chatPending = nil, 0
  setGameKeysEnabled(true)
end

local chatFlags = bit.bor(im.WindowFlags_NoTitleBar, im.WindowFlags_NoResize, im.WindowFlags_NoMove,
  im.WindowFlags_NoScrollbar, im.WindowFlags_NoSavedSettings, im.WindowFlags_NoBackground)

-- Minecraft draws the chat (history and the line being typed) in the overlay; this invisible text
-- box just collects the typing and passes it on.
local function updateChatInput(vp, dt)
  if not chatBuf then return end
  if chatPending > 0 then chatPending = chatPending - dt end
  if not onFoot or (not chatOpen and chatPending <= 0) then return closeChatInput() end
  setGameKeysEnabled(false)
  im.SetNextWindowPos(im.ImVec2(vp.Pos.x, vp.Pos.y + vp.Size.y - 60), im.Cond_Always)
  im.SetNextWindowSize(im.ImVec2(vp.Size.x * 0.5, 40), im.Cond_Always)
  im.PushStyleColor1(im.Col_Text, transparent)
  im.PushStyleColor1(im.Col_FrameBg, transparent)
  im.PushStyleColor1(im.Col_Border, transparent)
  local entered, cancelled = false, false
  if im.Begin('##BeamCraftChat', nil, chatFlags) then
    if chatFocus then
      pcall(im.SetKeyboardFocusHere, 0)
      chatFocus = false
    end
    im.InputText('##bcchat', chatBuf, 257)
    entered = im.IsKeyPressed(im.Key_Enter)
    cancelled = im.IsKeyPressed(im.Key_Escape)
  end
  im.End()
  im.PopStyleColor(3)
  local text = ffi.string(chatBuf)
  if text ~= chatLastText then
    chatLastText = text
    send('chattext ' .. text)
  end
  if entered then
    send('chattext ' .. text)
    send('chatsend')
    closeChatInput()
  elseif cancelled then
    send('press close')
    closeChatInput()
  end
end

-- UTF-8 string -> list of code points
local function codepoints(s)
  local out, i = {}, 1
  while i <= #s do
    local c = s:byte(i)
    local n = c < 0x80 and 1 or c < 0xE0 and 2 or c < 0xF0 and 3 or 4
    local cp = n == 1 and c or (c % (2 ^ (7 - n)))
    for k = 1, n - 1 do cp = cp * 64 + ((s:byte(i + k) or 128) % 64) end
    out[#out + 1] = math.floor(cp)
    i = i + n
  end
  return out
end

local function closeScreenTyping()
  if not typeBuf then return end
  typeBuf, typeLast = nil, ''
  if not chatBuf then setGameKeysEnabled(true) end
end

-- Typing into a text box inside a Minecraft screen (creative search...): an invisible text box here
-- collects the keys and Minecraft gets the same characters / backspaces as real keyboard input.
local function updateScreenTyping()
  if chatBuf or not onFoot or not screenTyping then return closeScreenTyping() end
  if not typeBuf then
    typeBuf, typeLast = im.ArrayChar(129, ''), ''
  end
  setGameKeysEnabled(false)
  im.SetNextWindowPos(im.ImVec2(-200, -200), im.Cond_Always) -- off-screen; it only needs keyboard focus
  im.SetNextWindowSize(im.ImVec2(150, 40), im.Cond_Always)
  local enter, escape, backspace = false, false, false
  if im.Begin('##BeamCraftType', nil, chatFlags) then
    pcall(function()
      local okActive, active = pcall(im.IsAnyItemActive)
      if not (okActive and active) then pcall(im.SetKeyboardFocusHere, 0) end
      im.InputText('##bctype', typeBuf, 129)
      enter = im.IsKeyPressed(im.Key_Enter)
      escape = im.Key_Escape ~= nil and im.IsKeyPressed(im.Key_Escape)
      backspace = im.Key_Backspace ~= nil and im.IsKeyPressed(im.Key_Backspace)
    end)
  end
  im.End()
  local text = ffi.string(typeBuf)
  if text ~= typeLast then
    -- send the difference: backspace over what changed, then type the new part
    local old, new = codepoints(typeLast), codepoints(text)
    local same = 0
    while same < #old and same < #new and old[same + 1] == new[same + 1] do same = same + 1 end
    for _ = same + 1, #old do send('key 259') end
    if #new > same then
      local cps = {}
      for i = same + 1, #new do cps[#cps + 1] = tostring(new[i]) end
      send('chars ' .. table.concat(cps, ' '))
    end
    typeLast = text
  elseif #text == 0 and backspace then
    send('key 259') -- deleting text that was already in Minecraft's box
  end
  if enter then send('key 257') end
  if escape then
    send('key 256')
    closeScreenTyping()
  end
end

-- A transparent, click-through window covering the screen (the same way BeamNG's own minimap
-- draws over the game); the background draw list isn't shown during normal play.
local function drawHud()
  local vp = im.GetMainViewport()
  im.SetNextWindowPos(vp.Pos, im.Cond_Always)
  im.SetNextWindowSize(vp.Size, im.Cond_Always)
  im.PushStyleColor1(im.Col_WindowBg, transparent)
  im.PushStyleColor1(im.Col_Border, transparent)
  im.PushStyleColor1(im.Col_BorderShadow, transparent)
  im.PushStyleVar2(im.StyleVar_WindowPadding, im.ImVec2(0, 0))
  local ok, err = true, nil
  if im.Begin('##BeamCraftHud', nil, hudFlags) then
    ok, err = pcall(drawHudContents, vp, im.GetWindowDrawList())
  end
  im.End()
  im.PopStyleColor(3)
  im.PopStyleVar(1)
  if not ok then error(err) end
  updateChatInput(vp, lastDt)
  updateScreenTyping()
end

---------------------------------------------------------------- cars as obstacles

local function mcVec(v) return v.x, v.z, -v.y end -- BeamNG direction -> Minecraft direction

-- Each car near Steve as centre + half-length axis + half-width axis + half height (Minecraft
-- coordinates); Minecraft turns them into collision boxes. Also: a fast car touching Steve hits him.
sendCars = function(feet, dt)
  local parts, n = {}, 0
  for _, veh in ipairs(getAllVehicles()) do
    local id = veh:getID()
    if veh:getActive() and veh:getJBeamFilename() ~= 'unicycle' and be:getObjectOOBBIsInitialized(id) then
      local c = vec3(be:getObjectOOBBCenterXYZ(id))
      if c:distance(feet) < 25 then
        local axes = {vec3(be:getObjectOOBBHalfAxisXYZ(id, 0)), vec3(be:getObjectOOBBHalfAxisXYZ(id, 1)), vec3(be:getObjectOOBBHalfAxisXYZ(id, 2))}
        -- the most vertical axis is the height; of the other two the longer is the length
        table.sort(axes, function(p, q) return math.abs(p.z) / (p:length() + 1e-6) < math.abs(q.z) / (q:length() + 1e-6) end)
        local A, B, Hh = axes[1], axes[2], axes[3]
        if B:length() > A:length() then A, B = B, A end
        local cx, cy, cz = toMc(c)
        local ax, ay, az = mcVec(A)
        local bx, by, bz = mcVec(B)
        parts[#parts + 1] = string.format('%.3f %.3f %.3f %.3f %.3f %.3f %.3f %.3f %.3f %.3f',
          cx, cy, cz, ax, ay, az, bx, by, bz, math.abs(Hh.z) + 0.05)
        n = n + 1

        -- run over: Steve within the car's box (plus a margin) while the car moves fast
        local rel = feet + vec3(0, 0, 0.9) - c
        local inside = math.abs(rel:dot(A:normalized())) < A:length() + 0.4
          and math.abs(rel:dot(B:normalized())) < B:length() + 0.4
          and math.abs(rel.z) < math.abs(Hh.z) + 0.9
        local vel = vec3(veh:getVelocityXYZ())
        local speed = vel:length()
        if inside and speed > 3 and now - lastCarHit > 0.6 then
          lastCarHit = now
          local vx, vy, vz = mcVec(vel)
          send(string.format('carhit %.2f %.3f %.3f %.3f', speed, vx, vy + speed * 0.3, vz))
          log('I', logTag, string.format('car %d ran into Steve at %.1f m/s', id, speed))
        end
      end
    end
  end
  send('cars ' .. n .. (n > 0 and (' ' .. table.concat(parts, ' ')) or ''))
end

---------------------------------------------------------------- update loop

local function updateOnFoot(dt)
  local uni = unicycle()
  local feet = stevePos()
  if not uni or not feet then return end

  -- Minecraft already walks Steve on BeamNG's exact floor heights, so no visual correction any more
  -- (it used to pull the camera down into blocks BeamNG had no collision for yet)
  visualZOffset = visualZOffset * math.max(1 - dt * 12, 0)

  -- Steve's body: only visible in third person
  if steve then
    steve.hidden = (st.persp or 0) == 0
    if not steve.hidden then
      local q = quatFromDir(lookDir(look.yaw, 0), vec3(0, 0, 1))
      steve:setPosRot(feet.x, feet.y, feet.z - (st.sneak and 0.15 or 0), q.x, q.y, q.z, q.w)
    end
  end

  -- BeamNG's (frozen, invisible) walking body follows Steve so "F" finds the car next to him
  unicycleTimer = unicycleTimer - dt
  if unicycleTimer <= 0 then
    unicycleTimer = 0.1
    if uni:getPosition():distance(feet) > 1.0 then
      local q = quatFromDir(lookDir(look.yaw, 0), vec3(0, 0, 1))
      uni:setPositionRotation(feet.x, feet.y, feet.z + 0.1, q.x, q.y, q.z, q.w)
    end
  end

  -- BeamNG's real geometry around Steve, for his collisions
  geoUpdate(fromMc(st.x, st.y, st.z), dt, 60)

  -- nearby cars become solid boxes in Minecraft; a car moving into Steve knocks him over
  carsTimer = carsTimer - dt
  if carsTimer <= 0 then
    carsTimer = 0.1
    sendCars(feet, dt)
  end

  -- car under the crosshair
  targetTimer = targetTimer - dt
  if targetTimer <= 0 then
    targetTimer = 0.05
    local eye = feet + vec3(0, 0, st.eye or 1.62)
    target = findCarTarget(eye, lookDir(look.yaw, look.pitch), st.reach or 3)
  end

  -- remember a safe spot (real BeamNG ground right under Steve) to come back to; and if there is
  -- no ground under him at all, he has slipped out of the map: put him back there
  safeTimer = safeTimer - dt
  if safeTimer <= 0 then
    safeTimer = 0.5
    local here = fromMc(st.x, st.y, st.z)
    local ground = groundBelow(vec3(here.x, here.y, here.z + 2.2), 300)
    if ground and st.onGround and math.abs(ground - here.z) < 0.3 then
      safePos = vec3(here.x, here.y, ground)
    elseif not ground and not rayDown(vec3(here.x, here.y, here.z + 60)) and safePos then
      teleportSteve(safePos, look.yaw)
      showMessage('BeamCraft: you fell out of the map - put you back', 3)
    end
  end

  -- keep the ground around Steve mirrored
  terrainTimer = terrainTimer - dt
  if terrainTimer <= 0 then
    terrainTimer = 0.5
    queueTerrain(fromMc(st.x, st.y, st.z), cfg.terrainRadius)
  end
  processTerrain(20)

  -- lock the mouse for Minecraft-style looking, except in menus
  setMouseLock(not menuOpen and not screenOpen and be:getEnabled())

  -- lower BeamNG's frame cap while a Minecraft screen is open, so Minecraft gets more of the GPU
  local screenLimit = tonumber(cfg.screenFpsLimit) or 0
  applyFpsLimit((screenOpen and screenLimit > 0) and screenLimit or cfg.footFpsLimit)

  if layoutRetry then
    layoutRetry = layoutRetry - dt
    if layoutRetry <= 0 then
      layoutRetry = nil
      guihooks.trigger('appContainer:loadLayoutByType', 'beamcraft')
    end
  end

  local vp = im.GetMainViewport()
  -- Minecraft renders the overlay at (a fraction of) our resolution and at our frame rate
  if dt > 0 then fpsAvg = fpsAvg + (1 / dt - fpsAvg) * math.min(dt * 2, 1) end
  local fps = clamp(math.floor(fpsAvg / 10 + 0.5) * 10, 20, 60)
  local scale = clamp(cfg.hudResolution or 0.5, 0.25, 1)
  local size = math.floor(vp.Size.x * scale) .. ' ' .. math.floor(vp.Size.y * scale) .. ' ' .. math.floor(cfg.fov) .. ' ' .. fps
  if size ~= lastSizeSent and vp.Size.x > 0 then
    lastSizeSent = size
    send('size ' .. size)
  end

  -- while a Minecraft screen (inventory, chest...) is open, BeamNG's mouse drives it
  if screenOpen then
    local mp = im.GetMousePos()
    -- buttons from ImGui, or from BeamNG's own mouse bindings in case its UI layer has the mouse
    local left = im.IsMouseDown(0) or band(keys, keyBits.attack) ~= 0
    local right = im.IsMouseDown(1) or band(keys, keyBits.use) ~= 0
    local buttons = (left and 1 or 0) + (right and 2 or 0)
    local wheel = im.GetIO().MouseWheel or 0
    send(string.format('mouse %.4f %.4f %d %.2f', (mp.x - vp.Pos.x) / vp.Size.x, (mp.y - vp.Pos.y) / vp.Size.y, buttons, wheel))
  end

  local active = not screenOpen and not menuOpen
  send(string.format('in %.2f %.2f %d %d 1 %.3f', look.yaw, look.pitch, active and keys or 0, target and 1 or 0, target and target.dist or 0))
end

local function updateInCar(dt)
  -- While driving, park Steve (in Minecraft) next to the car on mirrored ground so he
  -- doesn't fall into the void.
  send('in 0 0 0 0 0')
  local driving = getPlayerVehicle(0)
  if driving and driving:getJBeamFilename() ~= 'unicycle' then lastCarId = driving:getID() end -- for getting out at its door
  parkTimer = parkTimer - dt
  if parkTimer > 0 or not inWorld() then return end
  parkTimer = 1
  local veh = getPlayerVehicle(0)
  if not veh then return end
  local p = veh:getPosition()
  local ground = groundBelow(p + vec3(0, 0, 2), 10)
  if not ground then return end
  local pos = vec3(p.x, p.y, ground)
  queueTerrain(pos, 2)
  processTerrain(100)
  local x, y, z = toMc(pos)
  send(string.format('park %.3f %.3f %.3f %.2f', x, y + 0.05, z, yawFromDir(veh:getDirectionVector())))
end

local function onUpdate(dtReal, dtSim, dtRaw)
  now = now + dtReal
  lastDt = dtReal
  if messageTime > 0 then messageTime = messageTime - dtReal end
  if not openSocket() then return end
  receive()
  receiveTcp(dtReal)

  if not origin then
    local level = getCurrentLevelIdentifier and getCurrentLevelIdentifier()
    local veh = getPlayerVehicle(0)
    if level and veh then
      origin = levelOrigin(level, veh:getPosition().z - 60)
      collectWater()
      log('I', logTag, string.format('Level %s -> Minecraft patch %d (x %d, z %d)', level, origin.slot, origin.x, origin.z))
      sendHello()
      helloTimer = 2
    end
  end

  helloTimer = helloTimer - dtReal
  if helloTimer <= 0 then
    helloTimer = 2
    if not connected() or not inWorld() then sendHello() end
    -- Minecraft's time of day follows BeamNG's sun, so Steve and the hand are lit to match
    -- (BeamNG: noon at 0, midnight at 0.5; Minecraft: 6000 = noon)
    local tod = core_environment and core_environment.getTimeOfDay and core_environment.getTimeOfDay()
    if tod and tod.time and inWorld() then
      send(string.format('time %d', math.floor((tod.time * 24000 + 6000) % 24000)))
    end
  end

  if origin then
    local walking = gameplay_walk and gameplay_walk.isWalking and gameplay_walk.isWalking()
    if walking and not onFoot and inWorld() then startOnFoot()
    elseif onFoot and (not walking or not inWorld()) then stopOnFoot() end
    advanceClock(dtReal)
    updateMenuOpen()
    if onFoot then updateOnFoot(dtReal) else updateInCar(dtReal) end
    diagTimer = diagTimer - dtReal
    if diagTimer <= 0 and onFoot then
      diagTimer = 10
      log('I', logTag, string.format('on foot: %d HUD frames in 10 s, %d positions buffered, view %d, screen open %s',
        hudFramesReceived, #snaps, st and st.persp or -1, tostring(screenOpen)))
      hudFramesReceived = 0
    end
  end

  if collisionDirty then
    collisionTimer = collisionTimer - dtReal
    if collisionTimer <= 0 then
      collisionTimer = 0.25
      if collisionNeededNow() then
        collisionDirty = false
        table.clear(dirtyPoints)
        dirtyEverywhere = false
        be:reloadCollision()
        table.clear(collided)
        for key in pairs(blocks) do if not decor[key] then collided[key] = true end end
      end
    end
  end

  flush()
  local ok, err = pcall(drawHud)
  if not ok and not hudErrorLogged then
    hudErrorLogged = true
    log('E', logTag, 'HUD error: ' .. tostring(err))
  end
end

-- walk.lua resets the walking body's visibility every frame; hide it again right before rendering
local function onPreRender()
  if not onFoot then return end
  local uni = unicycle()
  if uni then
    uni:setMeshAlpha(0, '', false)
    uni.hidden = true
  end
end

local hookMenuOpen = false
local function onMenuToggled(show)
  hookMenuOpen = show and true or false
end

-- BeamNG's (Vue) menu doesn't always send onMenuToggled, so ask its UI router which screen is up.
updateMenuOpen = function()
  local cur = ui_router and ui_router.getCurrent and ui_router.getCurrent()
  local name = cur and cur.toRoute and cur.toRoute.name
  local wasOpen = menuOpen
  if name then menuOpen = name ~= 'play' else menuOpen = hookMenuOpen end
  -- Esc that closed a Minecraft screen must not also open BeamNG's menu (in case BeamNG's own
  -- Esc binding saw the key too): close it again straight away
  if menuOpen and not wasOpen and onFoot and now - escClosedScreenAt < 0.5 then
    escClosedScreenAt = -10
    guihooks.trigger('UINavigation', 'menu', 1)
    guihooks.trigger('UINavigation', 'menu', 0)
  end
end

local function resetLevel()
  if onFoot then stopOnFoot() end
  clearBlocks()
  table.clear(collided)
  deleteSteve()
  origin = nil
  st = nil
  table.clear(snaps)
  renderTick, tpGuard = nil, nil
  table.clear(terrainQueue)
  terrainCenter = nil
end

local function onClientStartMission()
  resetLevel()
end

local function onClientEndMission()
  resetLevel()
end

local function onExtensionLoaded()
  loadSettings()
  restoreFpsLimit() -- in case the last session ended while BeamCraft had the frame cap changed
  openSocket()
  -- F5 is BeamNG's "reload UI" key, which would wipe the Minecraft overlay. On foot it is
  -- Minecraft's F5 (first / third person) instead.
  if reloadUI and not originalReloadUI then
    originalReloadUI = reloadUI
    reloadUI = function(...)
      if onFoot then return M.onPress('perspective') end
      return originalReloadUI(...)
    end
  end
  log('I', logTag, 'BeamCraft loaded')
end

local function onExtensionUnloaded()
  resetLevel()
  restoreFpsLimit()
  if originalReloadUI then reloadUI, originalReloadUI = originalReloadUI, nil end
  if quickAccessToggle and core_quickAccess then core_quickAccess.toggle, quickAccessToggle = quickAccessToggle, nil end
  closeTcp()
  if udp then
    send('bye')
    flush()
    udp:close()
    udp = nil
  end
end

M.onUpdate = onUpdate
M.onPreRender = onPreRender
M.onMenuToggled = onMenuToggled
M.onClientStartMission = onClientStartMission
M.onClientEndMission = onClientEndMission
M.onExtensionLoaded = onExtensionLoaded
M.onExtensionUnloaded = onExtensionUnloaded

-- the HUD overlay app asks for the current frame when it (re)appears
M.resendHud = function()
  if lastHudImage and onFoot then guihooks.trigger('BeamCraftHudFrame', lastHudImage) end
end

-- console helpers
M.reloadSettings = loadSettings
M.status = function()
  return {connected = connected(), inWorld = inWorld(), mcStatus = mcStatus, onFoot = onFoot, origin = origin,
          blocks = tableSize(blocks), steve = st}
end

return M
