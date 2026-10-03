package com.ywemay.robotcar.web

/**
 * The remote-control page served at `GET /`.
 *
 * Kept as a Kotlin raw-string constant on purpose: a single self-contained
 * document (no external CSS/JS, no CDN) means the page works on a car with no
 * internet — only the phone's LAN — and there is nothing to bundle/asset-merge.
 *
 * NOTE: this is a Kotlin raw string, so `$` and `"""` are special. The inline
 * JavaScript therefore uses string concatenation, never ES template literals.
 */
internal object ControlPage {

    val HTML: String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0e1216">
<title>Robot Car Control</title>
<style>
:root{--bg:#0e1216;--panel:#161c22;--panel2:#232c34;--line:#2b353d;--fg:#e3e7ea;--dim:#8fa3b0;--accent:#4fc3f7;--ok:#43e97b;--bad:#ff6b6b;--warn:#ffc46b}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0}
body{background:radial-gradient(120% 90% at 50% 0%,#152029 0%,var(--bg) 60%);color:var(--fg);font:15px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;min-height:100vh;max-width:560px;margin:0 auto;padding:14px;display:flex;flex-direction:column;gap:12px}
header{display:flex;align-items:center;gap:12px;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:12px 14px}
.dot{width:15px;height:15px;border-radius:50%;background:var(--dim);flex:none}
.dot.ok{background:var(--ok);box-shadow:0 0 10px var(--ok)}
.dot.bad{background:var(--bad);box-shadow:0 0 10px var(--bad)}
.t{font-size:11px;letter-spacing:1.4px;color:var(--dim)}
.v{font-weight:700;font-size:16px}
.u{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11px;color:var(--dim);word-break:break-all}
section{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:14px}
.split{display:flex;justify-content:space-between;align-items:baseline}
.lbl{font-size:11px;letter-spacing:1.4px;color:var(--dim);margin-bottom:10px}
.lbl b{color:var(--accent);font-family:ui-monospace,monospace;letter-spacing:0}
.pad{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
.pad button{height:62px;border:0;border-radius:14px;background:var(--panel2);color:var(--fg);font-size:22px;font-weight:700;cursor:pointer;touch-action:none;user-select:none;-webkit-user-select:none;transition:background .07s,transform .07s}
.pad button.hot{background:var(--accent);color:#00222f;transform:scale(.97)}
.pad .stop{background:#8e2a22;color:#fff}
.pad .stop.hot{background:var(--bad);color:#fff}
.ghost{visibility:hidden}
.slider{display:flex;flex-direction:column;gap:2px;margin-bottom:8px}
.slider .row{display:flex;justify-content:space-between;font-size:13px}
.slider .val{font-family:ui-monospace,monospace;color:var(--accent)}
input[type=range]{width:100%;accent-color:var(--accent);height:26px}
.log{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11px;color:var(--dim);max-height:120px;overflow:auto;white-space:pre-wrap;word-break:break-all}
.hint{font-size:11px;color:var(--dim);text-align:center;padding-bottom:6px}
.moods{display:grid;grid-template-columns:repeat(auto-fill,minmax(88px,1fr));gap:8px}
.moods button{display:flex;align-items:center;justify-content:center;gap:7px;height:52px;border:1px solid var(--line);border-radius:12px;background:var(--panel2);color:var(--fg);font:600 13px system-ui,sans-serif;cursor:pointer;transition:background .1s,transform .07s,border-color .1s}
.moods button .e{font-size:18px;line-height:1}
.moods button.on{background:var(--accent);border-color:var(--accent);color:#00222f;transform:translateY(-1px)}
</style>
</head>
<body>
<header>
  <div class="dot" id="dot"></div>
  <div style="min-width:0">
    <div class="t">ROBOT CAR &middot; REMOTE</div>
    <div class="v" id="state">connecting&hellip;</div>
    <div class="u" id="url"></div>
  </div>
</header>

<section>
  <div class="lbl">FACE MOOD &middot; what the car looks like right now</div>
  <div class="moods" id="moods"></div>
</section>

<section>
  <div class="lbl">DRIVE &middot; hold to move, release to stop</div>
  <div class="pad">
    <div class="ghost"></div>
    <button data-dir="F" id="bF">&#9650;</button>
    <div class="ghost"></div>
    <button data-dir="L" id="bL">&#9664;</button>
    <button data-dir="S" id="bS" class="stop">&#9632;</button>
    <button data-dir="R" id="bR">&#9654;</button>
    <div class="ghost"></div>
    <button data-dir="B" id="bB">&#9660;</button>
    <div class="ghost"></div>
  </div>
</section>

<section>
  <div class="lbl">CAMERA GIMBAL</div>
  <div class="slider">
    <div class="row"><span>Pan</span><span class="val" id="panv">90&deg;</span></div>
    <input id="pan" type="range" min="0" max="180" value="90">
  </div>
  <div class="slider">
    <div class="row"><span>Tilt</span><span class="val" id="tiltv">90&deg;</span></div>
    <input id="tilt" type="range" min="0" max="180" value="90">
  </div>
</section>

<section>
  <div class="lbl">ACTIVITY LOG</div>
  <div class="log" id="log"></div>
</section>

<div class="hint">W A S D / arrows to drive &middot; Space = stop</div>

<script>
(function(){
  "use strict";
  var dot=document.getElementById("dot"),
      stateEl=document.getElementById("state"),
      urlEl=document.getElementById("url"),
      logEl=document.getElementById("log"),
      pan=document.getElementById("pan"),
      tilt=document.getElementById("tilt"),
      panv=document.getElementById("panv"),
      tiltv=document.getElementById("tiltv"),
      moodsEl=document.getElementById("moods");

  function log(msg){
    var t=new Date().toTimeString().slice(0,8);
    logEl.textContent="["+t+"] "+msg+"\n"+logEl.textContent;
    if(logEl.textContent.length>6000){ logEl.textContent=logEl.textContent.slice(0,6000); }
  }

  function get(url){
    return fetch(url,{cache:"no-store"}).then(function(r){ return r.json(); }).catch(function(){ return null; });
  }

  // ---- drive: press-and-hold, release = stop ------------------------------
  var driving=null;

  function sendDrive(dir){
    get("/cmd?d="+dir).then(function(r){ if(r&&r.frame){ log("TX "+r.frame); } });
  }

  function hold(dir){
    if(driving===dir){ return; }
    var prev=driving;
    driving=dir;
    if(prev){ var pb=document.getElementById("b"+prev); if(pb){ pb.classList.remove("hot"); } }
    var b=document.getElementById("b"+dir);
    if(b){ b.classList.add("hot"); }
    sendDrive(dir);
  }

  function releaseAll(){
    if(driving===null){ return; }
    var last=driving;
    driving=null;
    var b=document.getElementById("b"+last);
    if(b){ b.classList.remove("hot"); }
    // STOP stays stopped; every other key/button ends with an explicit kill frame.
    if(last!=="S"){ sendDrive("S"); }
  }

  Array.prototype.forEach.call(document.querySelectorAll(".pad button"),function(btn){
    var dir=btn.getAttribute("data-dir");
    btn.addEventListener("pointerdown",function(e){ e.preventDefault(); hold(dir); });
    btn.addEventListener("pointerup",function(e){ e.preventDefault(); releaseAll(); });
    btn.addEventListener("pointercancel",function(){ releaseAll(); });
    btn.addEventListener("pointerleave",function(){ if(driving===dir){ releaseAll(); } });
    btn.addEventListener("contextmenu",function(e){ e.preventDefault(); });
    btn.addEventListener("dragstart",function(e){ e.preventDefault(); });
  });

  var keyMap={KeyW:"F",KeyS:"B",KeyA:"L",KeyD:"R",
              ArrowUp:"F",ArrowDown:"B",ArrowLeft:"L",ArrowRight:"R",
              Space:"S"};
  document.addEventListener("keydown",function(e){
    var d=keyMap[e.code];
    if(!d){ return; }
    e.preventDefault();
    if(e.repeat){ return; }
    hold(d);
  });
  document.addEventListener("keyup",function(e){
    var d=keyMap[e.code];
    if(!d){ return; }
    e.preventDefault();
    if(driving===d){ releaseAll(); }
  });

  // ---- camera sliders -----------------------------------------------------
  var panDrag=false, tiltDrag=false, camTimer=null;

  function sendCamera(){
    var p=parseInt(pan.value,10), t=parseInt(tilt.value,10);
    get("/camera?pan="+p+"&tilt="+t).then(function(r){ if(r&&r.frame){ log("TX "+r.frame); } });
  }

  function scheduleCamera(){
    if(camTimer){ return; }
    camTimer=setTimeout(function(){ camTimer=null; sendCamera(); },90);
  }

  pan.addEventListener("input",function(){ panv.textContent=pan.value+"\u00B0"; panDrag=true; scheduleCamera(); });
  tilt.addEventListener("input",function(){ tiltv.textContent=tilt.value+"\u00B0"; tiltDrag=true; scheduleCamera(); });
  pan.addEventListener("change",function(){ panDrag=false; });
  tilt.addEventListener("change",function(){ tiltDrag=false; });

  // ---- face mood ----------------------------------------------------------
  // The button list is fetched, not hard-coded, so the Emotion enum on the
  // phone stays the single source of truth.
  var moodShown=null;

  function paintMood(slug){
    if(slug===moodShown){ return; }
    moodShown=slug;
    var kids=moodsEl.children;
    for(var i=0;i<kids.length;i++){
      kids[i].className=(kids[i].getAttribute("data-slug")===slug)?"on":"";
    }
  }

  function sendMood(slug){
    get("/emotion?e="+encodeURIComponent(slug)).then(function(r){
      if(r&&r.ok){ paintMood(r.emotion); log("MOOD "+r.label); }
    });
  }

  function buildMoods(){
    get("/emotions").then(function(r){
      if(!r||!r.emotions){ return; }
      moodsEl.textContent="";
      r.emotions.forEach(function(m){
        var b=document.createElement("button");
        b.setAttribute("data-slug",m.slug);
        var e=document.createElement("span");
        e.className="e";
        e.textContent=m.emoji;
        var t=document.createElement("span");
        t.textContent=m.label;
        b.appendChild(e);
        b.appendChild(t);
        b.addEventListener("click",function(){ sendMood(m.slug); });
        moodsEl.appendChild(b);
      });
      paintMood(r.current);
    });
  }
  buildMoods();

  // ---- status poll --------------------------------------------------------
  function poll(){
    get("/status").then(function(s){
      if(!s){ dot.className="dot bad"; stateEl.textContent="no response"; return; }
      urlEl.textContent=s.address||"";
      if(s.usb==="connected"){ dot.className="dot ok"; stateEl.textContent="USB connected"; }
      else { dot.className="dot bad"; stateEl.textContent="No USB link"; }
      if(!panDrag){ pan.value=s.pan; panv.textContent=s.pan+"\u00B0"; }
      if(!tiltDrag){ tilt.value=s.tilt; tiltv.textContent=s.tilt+"\u00B0"; }
      if(s.emotion){ paintMood(s.emotion); }
    });
  }
  setInterval(poll,1200);
  poll();
  log("remote control ready");
})();
</script>
</body>
</html>
""".trimIndent()
}
