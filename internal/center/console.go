package center

import (
	"net/http"
)

func serveConsole(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		methodNotAllowed(w, http.MethodGet, http.MethodHead)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'")
	if r.Method == http.MethodGet {
		_, _ = w.Write([]byte(consoleHTML))
	}
}

const consoleHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Remote Connect MCP</title>
  <style>
    :root{color-scheme:dark;--bg:#0b0d10;--panel:#13171c;--line:#27303a;--text:#edf2f7;--muted:#94a3b8;--accent:#56c2ff;--ok:#40d17b;--warn:#ffbd59;--bad:#ff6b6b}
    *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
    header{height:64px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;padding:0 24px;position:sticky;top:0;background:rgba(11,13,16,.96);z-index:2}
    h1{font-size:18px;margin:0;letter-spacing:.2px}main{max-width:1440px;margin:0 auto;padding:24px;display:grid;gap:18px}
    .stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.stat,.panel{background:var(--panel);border:1px solid var(--line);border-radius:10px}.stat{padding:16px}.stat b{font-size:25px;display:block}.stat span,.muted{color:var(--muted)}
    .layout{display:grid;grid-template-columns:minmax(300px,420px) minmax(0,1fr);gap:18px}.panel{overflow:hidden}.panel h2{font-size:14px;margin:0;padding:14px 16px;border-bottom:1px solid var(--line)}
    .body{padding:16px}.row{display:flex;gap:10px;align-items:center}.stack{display:grid;gap:10px}input,select,textarea,button{font:inherit;color:inherit;background:#0e1217;border:1px solid var(--line);border-radius:7px;padding:9px 10px}input,select,textarea{width:100%}textarea{min-height:120px;resize:vertical;font-family:ui-monospace,SFMono-Regular,Consolas,monospace}button{cursor:pointer;background:#18212a}button.primary{background:#1476a8;border-color:#238bc0}button.danger{color:#ff9d9d}button:hover{border-color:#526273}
    table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:10px 12px;border-bottom:1px solid var(--line);vertical-align:top}th{color:var(--muted);font-weight:500}tbody tr{cursor:pointer}tbody tr:hover{background:#171d24}.pill{display:inline-flex;padding:2px 8px;border-radius:999px;border:1px solid var(--line);font-size:12px}.online{color:var(--ok)}.offline{color:var(--muted)}.failed,.canceled{color:var(--bad)}.running,.dispatching{color:var(--accent)}.queued,.cancel_requested{color:var(--warn)}
    pre{margin:0;min-height:220px;max-height:520px;overflow:auto;white-space:pre-wrap;word-break:break-word;background:#080a0d;padding:14px;border-radius:7px;border:1px solid var(--line);font:12px/1.55 ui-monospace,SFMono-Regular,Consolas,monospace}.login{max-width:420px;margin:14vh auto;padding:24px}.hidden{display:none!important}.error{color:var(--bad)}
    @media(max-width:850px){.layout{grid-template-columns:1fr}.stats{grid-template-columns:1fr}header{padding:0 14px}main{padding:14px}.table-wrap{overflow:auto}}
  </style>
</head>
<body>
  <section id="login" class="panel login stack">
    <h1>Remote Connect MCP</h1><p class="muted">输入 Center 管理令牌。令牌只保存在当前浏览器标签页。</p>
    <input id="token" type="password" autocomplete="current-password" placeholder="管理令牌">
    <button class="primary" id="loginButton">连接 Center</button><div id="loginError" class="error"></div>
  </section>
  <div id="app" class="hidden">
    <header><h1>Remote Connect MCP · Control Center</h1><div class="row"><span id="refreshState" class="muted"></span><button id="logout">退出</button></div></header>
    <main>
      <section class="stats"><div class="stat"><b id="machineCount">0</b><span>已注册机器</span></div><div class="stat"><b id="onlineCount">0</b><span>在线机器</span></div><div class="stat"><b id="runningCount">0</b><span>活动任务</span></div><div class="stat"><b id="upgradeCount">0</b><span>升级活动</span></div></section>
      <section class="layout">
        <div class="panel"><h2>创建任务</h2><div class="body stack">
          <select id="machine"></select><input id="cwd" placeholder="工作目录（留空使用 Agent 默认目录）">
          <textarea id="command" spellcheck="false" placeholder="输入 Shell 命令"></textarea>
          <div class="row"><input id="timeout" type="number" min="0" value="0" title="0 表示不限制运行时间"><button class="primary" id="run">开始执行</button></div><div id="runError" class="error"></div>
        </div></div>
        <div class="panel"><h2>机器</h2><div class="table-wrap"><table><thead><tr><th>名称</th><th>平台</th><th>状态</th><th>最后心跳</th></tr></thead><tbody id="machines"></tbody></table></div></div>
      </section>
      <section class="panel"><h2>Agent 无感升级</h2><div class="body stack">
        <div class="row"><input id="upgradeVersion" placeholder="Release 版本，例如 v1.3.1"><input id="canaryCount" type="number" min="1" value="1" title="首批 canary 数量"><input id="batchSize" type="number" min="1" value="3" title="后续每批数量"><button class="primary" id="startUpgrade">升级全部在线机器</button></div>
        <div class="muted">Center 自动解析 GitHub Release 的跨平台原始 Agent 和 SHA-256；canary 成功后自动推进批次，任一失败立即暂停。</div><div id="upgradeError" class="error"></div>
        <div class="table-wrap"><table><thead><tr><th>活动</th><th>版本</th><th>状态</th><th>进度</th><th>批次</th><th>操作</th></tr></thead><tbody id="upgrades"></tbody></table></div>
      </div></section>
      <section class="panel"><h2>任务</h2><div class="table-wrap"><table><thead><tr><th>任务</th><th>机器</th><th>状态</th><th>命令</th><th>创建时间</th></tr></thead><tbody id="tasks"></tbody></table></div></section>
      <section class="panel"><h2>任务输出</h2><div class="body stack"><div class="row"><span id="selectedTask" class="muted">选择一个任务</span><button id="cancel" class="danger" disabled>取消任务</button><button id="reloadOutput" disabled>刷新输出</button></div><pre id="output"></pre></div></section>
    </main>
  </div>
  <script>
    const $=id=>document.getElementById(id);let token=sessionStorage.getItem('remoteConnectMcpAdminToken')||'';let machines=[],tasks=[],upgrades=[],selected='';
    async function api(path,options={}){const headers={...(options.headers||{}),Authorization:'Bearer '+token};if(options.body)headers['Content-Type']='application/json';const response=await fetch('/api/v1/'+path,{...options,headers});const body=await response.json().catch(()=>({error:'响应不是 JSON'}));if(!response.ok)throw new Error(body.error||('HTTP '+response.status));return body}
    function esc(value){return String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
    function date(value){if(!value)return '—';return new Date(value).toLocaleString()}
    async function login(){token=$('token').value.trim();try{await api('machines');sessionStorage.setItem('remoteConnectMcpAdminToken',token);$('login').classList.add('hidden');$('app').classList.remove('hidden');$('loginError').textContent='';await refresh()}catch(error){$('loginError').textContent=error.message}}
    async function refresh(){try{const [machineData,taskData,upgradeData]=await Promise.all([api('machines'),api('tasks?limit=100'),api('upgrades?limit=20')]);machines=machineData.machines||[];tasks=taskData.tasks||[];upgrades=upgradeData.upgrades||[];render();$('refreshState').textContent='已刷新 '+new Date().toLocaleTimeString()}catch(error){$('refreshState').textContent='刷新失败：'+error.message}}
    function render(){const names=Object.fromEntries(machines.map(m=>[m.id,m.name]));$('machineCount').textContent=machines.length;$('onlineCount').textContent=machines.filter(m=>m.online).length;$('runningCount').textContent=tasks.filter(t=>['queued','dispatching','running','cancel_requested'].includes(t.status)).length;$('upgradeCount').textContent=upgrades.filter(u=>['running','paused'].includes(u.status)).length;
      const chosen=$('machine').value;$('machine').innerHTML=machines.map(m=>'<option value="'+esc(m.id)+'">'+esc(m.name)+(m.online?' · 在线':' · 离线')+'</option>').join('');if(chosen)$('machine').value=chosen;
      $('machines').innerHTML=machines.map(m=>'<tr><td><b>'+esc(m.name)+'</b><br><span class="muted">'+esc(m.id)+'</span></td><td>'+esc(m.os)+' / '+esc(m.arch)+'</td><td><span class="pill '+(m.online?'online':'offline')+'">'+(m.online?'在线':'离线')+'</span></td><td>'+date(m.last_seen)+'</td></tr>').join('');
      $('upgrades').innerHTML=upgrades.map(u=>{const done=(u.targets||[]).filter(t=>t.status==='completed').length,failed=(u.targets||[]).filter(t=>t.status==='failed').length,total=(u.targets||[]).length;const actions=u.status==='paused'?'<button data-upgrade="'+esc(u.id)+'" data-action="resume">重试并继续</button>':(u.status==='running'?'<button class="danger" data-upgrade="'+esc(u.id)+'" data-action="cancel">取消</button>':'');return '<tr><td>'+esc(u.id)+'</td><td><b>'+esc(u.version)+'</b></td><td><span class="pill '+esc(u.status)+'">'+esc(u.status)+'</span></td><td>'+done+' / '+total+(failed?' · 失败 '+failed:'')+'</td><td>canary '+esc(u.canary_count)+' · 每批 '+esc(u.batch_size)+'</td><td>'+actions+'</td></tr>'}).join('');document.querySelectorAll('[data-upgrade]').forEach(button=>button.onclick=()=>upgradeAction(button.dataset.upgrade,button.dataset.action));
      $('tasks').innerHTML=tasks.map(t=>'<tr data-task="'+esc(t.id)+'"><td>'+esc(t.id)+'</td><td>'+esc(names[t.machine_id]||t.machine_id)+'</td><td><span class="pill '+esc(t.status)+'">'+esc(t.status)+'</span></td><td><code>'+esc(t.command).slice(0,160)+'</code></td><td>'+date(t.created_at)+'</td></tr>').join('');document.querySelectorAll('[data-task]').forEach(row=>row.onclick=()=>selectTask(row.dataset.task));}
    async function run(){const payload={machine_id:$('machine').value,command:$('command').value,cwd:$('cwd').value,timeout_seconds:Number($('timeout').value||0)};$('runError').textContent='';try{const task=await api('tasks',{method:'POST',body:JSON.stringify(payload)});selected=task.id;$('selectedTask').textContent=selected;$('cancel').disabled=false;$('reloadOutput').disabled=false;await refresh();await loadOutput()}catch(error){$('runError').textContent=error.message}}
    async function selectTask(id){selected=id;$('selectedTask').textContent=id;$('cancel').disabled=false;$('reloadOutput').disabled=false;await loadOutput()}
    async function loadOutput(){if(!selected)return;try{const result=await api('tasks/'+encodeURIComponent(selected)+'/output?cursor=0&limit=1048576');$('output').textContent=result.text||'';$('output').scrollTop=$('output').scrollHeight}catch(error){$('output').textContent='读取失败：'+error.message}}
    async function cancel(){if(!selected)return;await api('tasks/'+encodeURIComponent(selected)+'/cancel',{method:'POST'});await refresh()}
    async function startUpgrade(){const payload={version:$('upgradeVersion').value.trim(),canary_count:Number($('canaryCount').value||1),batch_size:Number($('batchSize').value||3)};$('upgradeError').textContent='';try{await api('upgrades',{method:'POST',body:JSON.stringify(payload)});await refresh()}catch(error){$('upgradeError').textContent=error.message}}
    async function upgradeAction(id,action){$('upgradeError').textContent='';try{await api('upgrades/'+encodeURIComponent(id)+'/'+action,{method:'POST'});await refresh()}catch(error){$('upgradeError').textContent=error.message}}
    $('loginButton').onclick=login;$('token').onkeydown=e=>{if(e.key==='Enter')login()};$('run').onclick=run;$('cancel').onclick=cancel;$('reloadOutput').onclick=loadOutput;$('startUpgrade').onclick=startUpgrade;$('logout').onclick=()=>{sessionStorage.removeItem('remoteConnectMcpAdminToken');location.reload()};
    if(token){$('token').value=token;login()}setInterval(()=>{if(!$('app').classList.contains('hidden')){refresh();if(selected)loadOutput()}},3000);
  </script>
</body>
</html>`
