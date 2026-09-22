import React from 'react'

export type MetricTone = 'blue' | 'emerald' | 'amber' | 'purple'

interface MetricCardProps {
  label: string
  value: string | number
  meta: string
  tone?: MetricTone
  icon: React.ReactNode
}

export function MetricCard({ label, value, meta, tone = 'blue', icon }: MetricCardProps) {
  return (
    <div className={`metric-card ${tone}`}>
      <div className="metric-card-top">
        <span className="metric-card-label">{label}</span>
        <div className="metric-icon-box">{icon}</div>
      </div>
      <div className="metric-card-val">{value}</div>
      <div className="metric-card-meta">
        <span>{meta}</span>
      </div>
    </div>
  )
}
