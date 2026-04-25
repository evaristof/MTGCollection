import { useState } from 'react'
import { api } from '../api/client'

interface SetIconProps {
  setCode: string
  setName: string
  size?: number
}

export function SetIcon({ setCode, setName, size = 20 }: SetIconProps) {
  const [error, setError] = useState(false)

  if (error) {
    return null
  }

  return (
    <img
      src={api.setIconUrl(setCode)}
      alt={`${setName} icon`}
      width={size}
      height={size}
      style={{ verticalAlign: 'middle' }}
      onError={() => setError(true)}
    />
  )
}
