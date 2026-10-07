// BeamCraft: shows what Minecraft draws over the game (hand, Steve, HUD, inventory, chat).
// Frames are rendered by Minecraft (BeamCraft Fabric mod), passed through BeamNG's Lua and arrive
// here as a PNG of just the area that has something in it, plus where it goes on the screen.
angular.module('beamng.apps')
.directive('beamcraftHud', [function () {
  return {
    template:
      '<div style="position:fixed; left:0; top:0; width:100vw; height:100vh; pointer-events:none;">' +
        '<canvas style="width:100%; height:100%; pointer-events:none;"></canvas>' +
      '</div>',
    replace: true,
    link: function (scope, element) {
      'use strict'
      var wrapper = element[0]
      var canvas = wrapper.querySelector('canvas')
      var ctx = canvas.getContext('2d')
      var latest = 0

      // BeamNG scales its UI apps (UI scale setting), which would stretch the overlay so the hand
      // and crosshair no longer line up with the 3D view. Undo that: cover exactly the window.
      function fitToWindow () {
        wrapper.style.transform = ''
        var r = wrapper.getBoundingClientRect()       // where it really ends up on screen
        if (!wrapper.offsetWidth || !wrapper.offsetHeight || !r.width || !r.height) return
        var kx = r.width / wrapper.offsetWidth          // scaling applied by BeamNG's app container
        var ky = r.height / wrapper.offsetHeight
        var tx = -r.left / kx, ty = -r.top / ky
        var sx = window.innerWidth / r.width, sy = window.innerHeight / r.height
        wrapper.style.transformOrigin = '0 0'
        wrapper.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + sx + ',' + sy + ')'
      }
      setTimeout(fitToWindow, 0)
      var fitTimer = setInterval(fitToWindow, 2000)
      window.addEventListener('resize', fitToWindow)
      scope.$on('$destroy', function () {
        clearInterval(fitTimer)
        window.removeEventListener('resize', fitToWindow)
      })

      function show (frame, img) {
        if (canvas.width !== frame.fw || canvas.height !== frame.fh) {
          canvas.width = frame.fw
          canvas.height = frame.fh
        }
        // smooth scaling up to BeamNG's resolution softens Minecraft's jagged edges
        ctx.imageSmoothingEnabled = true
        ctx.imageSmoothingQuality = 'high'
        ctx.clearRect(0, 0, canvas.width, canvas.height)
        if (img) ctx.drawImage(img, frame.x, frame.y)
      }

      scope.$on('BeamCraftHudFrame', function (event, frame) {
        if (!frame) return
        var id = ++latest
        if (!frame.img) {
          show(frame, null)
          return
        }
        var img = new Image()
        img.onload = function () {
          if (id === latest) show(frame, img) // skip frames that a newer one overtook while decoding
        }
        img.src = frame.img
      })

      bngApi.engineLua('if beamcraft then beamcraft.resendHud() end')
    }
  }
}])
