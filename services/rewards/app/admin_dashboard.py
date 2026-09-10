from __future__ import annotations


DASHBOARD_HTML = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>JoyeDrama 奖励运营后台</title>
  <style>
    :root { color-scheme: dark; --bg:#121018; --panel:#201a2d; --accent:#ff5d69; --gold:#ffdb6a; --muted:#bfb5cd; }
    * { box-sizing:border-box; } body { margin:0; min-height:100vh; background:linear-gradient(135deg,#15101f,#101a28); color:#fff; font:15px system-ui,-apple-system,"Microsoft YaHei",sans-serif; }
    main { width:min(720px,calc(100% - 32px)); margin:32px auto; } h1 { margin:0 0 8px; font-size:27px; } .intro { color:var(--muted); line-height:1.65; }
    .card { background:rgba(32,26,45,.94); border:1px solid #403457; border-radius:18px; padding:20px; margin-top:18px; box-shadow:0 16px 40px rgba(0,0,0,.18); }
    label { display:block; color:var(--muted); margin:14px 0 7px; font-size:13px; } input { width:100%; border:1px solid #514466; border-radius:10px; background:#15111f; color:#fff; padding:12px; font-size:15px; }
    button { border:0; border-radius:10px; padding:12px 15px; margin:14px 8px 0 0; background:var(--accent); color:#fff; font-weight:700; cursor:pointer; } button.secondary { background:#332b43; color:var(--gold); }
    button:disabled { cursor:not-allowed; opacity:.55; } #status { min-height:22px; margin-top:14px; color:var(--muted); } #status.error { color:#ff9da4; } #status.ok { color:#9aebbd; }
    .metrics { display:grid; grid-template-columns:repeat(3,1fr); gap:10px; margin-top:16px; } .metric { background:#171222; border-radius:12px; padding:13px; } .metric span { display:block; color:var(--muted); font-size:12px; } .metric strong { display:block; margin-top:5px; color:var(--gold); font-size:18px; }
    table { width:100%; margin-top:18px; border-collapse:collapse; } th,td { padding:10px 6px; text-align:left; border-bottom:1px solid #3d334c; } th { color:var(--muted); font-size:12px; font-weight:600; }
    .notice { border-left:3px solid var(--gold); color:#e8ddf0; padding:8px 12px; background:#292136; border-radius:7px; line-height:1.55; font-size:13px; }
  </style>
</head>
<body><main>
  <h1>JoyeDrama 奖励运营后台</h1>
  <p class="intro">此页面仅调整<strong>十轮追剧额外奖励</strong>的递减速度。用户已完成、已领取或已生成的奖励不回溯变更；新生成的十轮奖励会使用最新规则。</p>
  <section class="card">
    <div class="notice">请设置并保存 <code>ADMIN_DASHBOARD_TOKEN</code> 后使用。令牌仅保存在本浏览器的会话内，并以请求头发送，不会写入数据库或页面地址。</div>
    <label for="token">管理员令牌</label><input id="token" type="password" autocomplete="off" placeholder="粘贴 ADMIN_DASHBOARD_TOKEN">
    <button class="secondary" id="load">读取当前规则</button>
    <div id="status"></div>
  </section>
  <section class="card" id="policy" hidden>
    <h2 style="margin:0;font-size:20px">十轮奖励阻尼</h2>
    <div class="metrics"><div class="metric"><span>首轮额外奖励</span><strong id="r0">–</strong></div><div class="metric"><span>最低保底</span><strong id="floor">–</strong></div><div class="metric"><span>每轮集数</span><strong id="roundSize">–</strong></div></div>
    <label for="alpha">阻尼系数 α（0.00–5.00）</label><input id="alpha" type="number" min="0" max="5" step="0.01">
    <div class="notice" id="formula">奖励公式：首轮奖励 ÷ 轮次^α，且不低于保底值。</div>
    <button id="save">保存阻尼系数</button><button class="secondary" id="preview">刷新奖励预览</button>
    <table><thead><tr><th>十轮奖励轮次</th><th>按当前系数预计宝石</th></tr></thead><tbody id="curve"></tbody></table>
  </section>
</main>
<script>
const $=id=>document.getElementById(id); let current=null;
function auth(){return {'Authorization':'Bearer '+$('token').value.trim(),'Content-Type':'application/json'};}
function status(text,type=''){const e=$('status');e.textContent=text;e.className=type;}
function render(p){current=p;$('policy').hidden=false;$('r0').textContent=p.r0_gems+' 宝石';$('floor').textContent=p.floor_gems+' 宝石';$('roundSize').textContent=p.ten_round_size+' 集';$('alpha').value=p.decay_alpha;curve();}
function curve(){if(!current)return;const a=Number($('alpha').value),r0=Number(current.r0_gems),floor=Number(current.floor_gems);$('curve').innerHTML=[1,2,3,5,10,20].map(n=>`<tr><td>第 ${n} 轮</td><td>${Math.max(Math.floor(r0/Math.pow(n,a)),floor)} 宝石</td></tr>`).join('');}
async function load(){status('正在读取规则…');try{const r=await fetch('/v2/admin/policy',{headers:auth()});const j=await r.json();if(!r.ok)throw Error(j.msg||'读取失败');render(j.data);status('规则已加载。','ok');}catch(e){status(e.message,'error');}}
async function save(){if(!current)return;const alpha=Number($('alpha').value);if(!Number.isFinite(alpha)||alpha<0||alpha>5){status('阻尼系数必须在 0.00 到 5.00 之间。','error');return;}status('正在保存…');try{const r=await fetch('/v2/admin/policy/damping',{method:'PUT',headers:auth(),body:JSON.stringify({decay_alpha:alpha})});const j=await r.json();if(!r.ok)throw Error(j.msg||'保存失败');render(j.data);status('已保存。之后新生成的十轮奖励将使用该系数。','ok');}catch(e){status(e.message,'error');}}
$('load').onclick=load;$('save').onclick=save;$('preview').onclick=curve;$('alpha').oninput=curve;
</script></body></html>"""
