import { api } from '@/utils/request'

export const getBrandList = (params?: { keyword?: string; edited?: boolean }) => {
  return api.get<Array<{ originalName: string; customName: string; discountScheme: number; edited: boolean }>>('/brands/list', { params })
}

export const saveBrandCustomName = (data: { originalName: string; customName: string; discountScheme: number }) => {
  return api.post<string>('/brands/save', data)
}

export const getBrandOptions = () => {
  return api.get<Array<{ value: string; label: string }>>('/brands/options')
}