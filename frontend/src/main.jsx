import { render } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import './styles.css';

const views = [
  { key: 'months', label: 'Month' },
  { key: 'weeks', label: 'Week' },
  { key: 'days', label: 'Day' }
];

const DEFAULT_CREDIT_COST = 0.065;

function App() {
  const [overview, setOverview] = useState(null);
  const [selectedView, setSelectedView] = useState('months');
  const [selectedBucket, setSelectedBucket] = useState(null);
  const [selectedSessionId, setSelectedSessionId] = useState(null);
  const [session, setSession] = useState(null);
  const [error, setError] = useState('');
  const [creditCost, setCreditCost] = useState(DEFAULT_CREDIT_COST);
  const displayScale = creditCost / DEFAULT_CREDIT_COST;

  useEffect(() => {
    fetch('/api/overview')
      .then(assertOk)
      .then((data) => {
        setOverview(data);
        const first = data.months?.[0]?.key;
        setSelectedBucket(first || null);
        setSelectedSessionId(data.sessions?.[0]?.id || null);
      })
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    if (!selectedSessionId) {
      setSession(null);
      return;
    }
    fetch(`/api/sessions/${encodeURIComponent(selectedSessionId)}`)
      .then(assertOk)
      .then(setSession)
      .catch((err) => setError(err.message));
  }, [selectedSessionId]);

  const buckets = overview?.[selectedView] || [];
  const currentBucket = buckets.find((bucket) => bucket.key === selectedBucket) || buckets[0];

  const visibleSessions = useMemo(() => {
    if (!overview || !currentBucket) {
      return [];
    }
    return overview.sessions.filter((item) => {
      const day = item.date;
      return day >= currentBucket.start && day <= currentBucket.end;
    });
  }, [overview, currentBucket]);

  useEffect(() => {
    if (visibleSessions.length && !visibleSessions.some((item) => item.id === selectedSessionId)) {
      setSelectedSessionId(visibleSessions[0].id);
    }
  }, [visibleSessions, selectedSessionId]);

  if (error) {
    return <main class="shell"><div class="notice">Failed to load report: {error}</div></main>;
  }

  if (!overview) {
    return <main class="shell"><div class="notice">Loading token usage...</div></main>;
  }

  const selectedSummary = overview.sessions.find((item) => item.id === selectedSessionId) || null;
  const detailSession = session || selectedSummary;

  return (
    <main class="shell">
      <header class="topbar">
        <div>
          <p class="eyebrow">Codex sessions</p>
          <h1>Token usage</h1>
          <p class="subtle">{overview.sessionsDir}</p>
        </div>
        <div class="topbar-actions">
          <label class="credit-control">
            <span>Credit cost</span>
            <div class="credit-input">
              <span>$</span>
              <input
                type="number"
                min="0"
                step="0.001"
                value={creditCost}
                onInput={(event) => setCreditCost(Number(event.currentTarget.value) || 0)}
              />
            </div>
          </label>
          <button class="icon-button" title="Refresh" aria-label="Refresh" onClick={() => location.reload()}>
            <RefreshIcon />
          </button>
        </div>
      </header>

      <section class="metrics" aria-label="Total usage">
        <Metric label="Sessions" value={formatNumber(overview.sessionCount)} />
        <Metric label="Cost" value={formatUsd(scaleUsd(overview.totalCost.totalUsd, displayScale))} />
        <Metric label="Total tokens" value={formatNumber(overview.totalUsage.totalTokens)} />
        <Metric label="Input" value={formatNumber(overview.totalUsage.inputTokens)} />
        <Metric label="Output" value={formatNumber(overview.totalUsage.outputTokens)} />
      </section>

      <section class="workspace">
        <aside class="rail" aria-label="Aggregate periods">
          <div class="segmented">
            {views.map((view) => (
              <button
                key={view.key}
                class={selectedView === view.key ? 'active' : ''}
                onClick={() => {
                  setSelectedView(view.key);
                  setSelectedBucket((overview[view.key] || [])[0]?.key || null);
                }}
              >
                {view.label}
              </button>
            ))}
          </div>
          <div class="bucket-list">
            {buckets.map((bucket) => (
              <button
                key={bucket.key}
                class={`bucket ${currentBucket?.key === bucket.key ? 'selected' : ''}`}
                onClick={() => setSelectedBucket(bucket.key)}
              >
                <span>
                  <strong>{bucket.label}</strong>
                  <small>{bucket.sessionCount} sessions</small>
                </span>
                <b>{formatUsd(scaleUsd(bucket.cost.totalUsd, displayScale))}</b>
              </button>
            ))}
          </div>
        </aside>

        <section class="sessions" aria-label="Sessions">
          <div class="section-head">
            <div>
              <p class="eyebrow">Drilldown</p>
              <h2>{currentBucket?.label || 'No sessions'}</h2>
            </div>
            {currentBucket && <UsagePills usage={currentBucket.usage} />}
          </div>

          <div class="session-table">
            <div class="row header">
              <span>Started</span>
              <span>Prompt</span>
              <span>Model</span>
              <span>Level</span>
              <span>Cost</span>
              <span>Total</span>
            </div>
            {visibleSessions.map((item) => (
              <button
                key={item.id}
                class={`row session-row ${item.id === selectedSessionId ? 'selected' : ''}`}
                onClick={() => setSelectedSessionId(item.id)}
              >
                <span>{formatDateTime(item.timestamp)}</span>
                <span class="title">{item.title}</span>
                <span>{item.model}</span>
                <span>{item.reasoningLevel || '-'}</span>
                <span>{formatUsd(scaleUsd(item.cost.totalUsd, displayScale))}</span>
                <span>{formatNumber(item.usage.totalTokens)}</span>
              </button>
            ))}
          </div>
        </section>

        <aside class="detail" aria-label="Session detail">
          {detailSession ? <SessionDetail session={detailSession} displayScale={displayScale} /> : <div class="notice">Select a session</div>}
        </aside>
      </section>
    </main>
  );
}

function SessionDetail({ session, displayScale }) {
  const prompts = session.prompts || [];

  return (
    <>
      <div class="section-head compact-head">
        <div>
          <p class="eyebrow">Session</p>
          <h2>{formatDateTime(session.timestamp)}</h2>
        </div>
      </div>
      <UsagePills usage={session.usage} />
      <div class="cost-panel">
        <strong>{formatUsd(scaleUsd(session.cost.totalUsd, displayScale))}</strong>
        <span>
          {formatUsd(scaleUsd(session.cost.inputUsd, displayScale))} input + {formatUsd(scaleUsd(session.cost.cachedInputUsd, displayScale))} cached + {formatUsd(scaleUsd(session.cost.outputUsd, displayScale))} output
        </span>
      </div>
      <dl class="meta">
        <div><dt>ID</dt><dd>{session.id}</dd></div>
        <div><dt>Model</dt><dd>{session.model}</dd></div>
        <div><dt>Level</dt><dd>{session.reasoningLevel || '-'}</dd></div>
        <div><dt>Rates / 1M</dt><dd>{formatUsd(scaleUsd(session.cost.rate.inputPerMillion, displayScale))} input, {formatUsd(scaleUsd(session.cost.rate.cachedInputPerMillion, displayScale))} cached, {formatUsd(scaleUsd(session.cost.rate.outputPerMillion, displayScale))} output</dd></div>
        <div><dt>CWD</dt><dd>{session.cwd || '-'}</dd></div>
        <div><dt>Source</dt><dd>{session.source || '-'}</dd></div>
        <div><dt>Token events</dt><dd>{session.tokenEvents}</dd></div>
        <div><dt>File</dt><dd>{session.path}</dd></div>
      </dl>
      <div class="prompt-head">
        <p class="eyebrow">Prompts</p>
        <span>{prompts.length}</span>
      </div>
      <div class="prompts">
        {prompts.length === 0 && <pre>No user prompt was found in this session.</pre>}
        {prompts.map((prompt, index) => (
          <pre key={index}>{prompt}</pre>
        ))}
      </div>
    </>
  );
}

function Metric({ label, value }) {
  return (
    <div class="metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function UsagePills({ usage }) {
  return (
    <div class="pills">
      <span>{compact(usage.totalTokens)} total</span>
      <span>{compact(usage.inputTokens)} in</span>
      <span>{compact(usage.outputTokens)} out</span>
      <span>{compact(usage.cachedInputTokens)} cached</span>
    </div>
  );
}

function RefreshIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M20 12a8 8 0 1 1-2.34-5.66" />
      <path d="M20 4v6h-6" />
    </svg>
  );
}

async function assertOk(response) {
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return response.json();
}

function formatNumber(value) {
  return new Intl.NumberFormat().format(value || 0);
}

function formatUsd(value) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(value || 0);
}

function scaleUsd(value, scale) {
  return (value || 0) * scale;
}

function compact(value) {
  return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(value || 0);
}

function formatDateTime(value) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit'
  }).format(new Date(value));
}

render(<App />, document.getElementById('app'));
