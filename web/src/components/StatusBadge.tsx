import React from 'react'

export type StatusTone = 'good' | 'success' | 'online' | 'warning' | 'amber' | 'paused' | 'danger' | 'failed' | 'error' | 'info' | 'running' | 'dispatching' | 'muted' | 'offline' | 'queued'

interface StatusBadgeProps {
  label: string
  tone?: StatusTone
  pulse?: boolean
  className?: string
}

export function StatusBadge({ label, tone = 'muted', pulse = false, className = '' }: StatusBadgeProps) {
  return (
    <span className={`status-pill ${tone} ${className}`}>
      {pulse ? <span className="pulse-dot" /> : <i />}
      <span>{label}</span>
    </span>
  )
}
