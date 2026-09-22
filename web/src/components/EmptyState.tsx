import React from 'react'
import { BoxIcon } from '../icons/Icons'

interface EmptyStateProps {
  title: string
  description?: string
  icon?: React.ReactNode
  action?: {
    label: string
    onClick: () => void
  }
}

export function EmptyState({
  title,
  description,
  icon = <BoxIcon size={24} />,
  action,
}: EmptyStateProps) {
  return (
    <div className="empty-state">
      <div className="empty-icon-wrap">{icon}</div>
      <h3 className="empty-title">{title}</h3>
      {description && <p className="empty-desc">{description}</p>}
      {action && (
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={action.onClick}
          style={{ marginTop: '16px' }}
        >
          {action.label}
        </button>
      )}
    </div>
  )
}
