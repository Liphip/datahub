import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Tenant } from '../types';

export default function Tenants() {
  const { user } = useAuthStore();
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchTenants = async () => {
      try {
        const data = await api.getTenants();
        setTenants(data);
      } catch (error) {
        console.error('Error fetching tenants:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchTenants();
  }, []);

  if (!user?.isSuperadmin) {
    return (
      <div className="px-4 py-6">
        <p className="text-gray-500">Access denied. Admin privileges required.</p>
      </div>
    );
  }

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Tenants</h1>
        <button className="btn-primary">
          Create Tenant
        </button>
      </div>

      {loading ? (
        <div className="space-y-4">
          {[1, 2, 3].map(i => (
            <div key={i} className="card animate-pulse">
              <div className="h-4 bg-gray-200 rounded w-1/3"></div>
            </div>
          ))}
        </div>
      ) : (
        <div className="space-y-4">
          {tenants.map((tenant) => (
            <div key={tenant.id} className="card">
              <div className="flex justify-between items-start">
                <div>
                  <h3 className="text-lg font-semibold">{tenant.name}</h3>
                  <p className="text-sm text-gray-500">ID: {tenant.id}</p>
                  {tenant.parentId && (
                    <p className="text-sm text-gray-500">Parent: {tenant.parentId}</p>
                  )}
                </div>
                <div className="flex space-x-2">
                  <button className="btn-secondary text-sm">Edit</button>
                  <button className="text-red-600 hover:text-red-700 text-sm">Delete</button>
                </div>
              </div>
              {tenant.children.length > 0 && (
                <div className="mt-4 pl-4 border-l-2 border-gray-200">
                  <p className="text-sm font-medium text-gray-700 mb-2">Child Tenants:</p>
                  <div className="space-y-2">
                    {tenant.children.map((child) => (
                      <div key={child.id} className="text-sm text-gray-600">
                        {child.name}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
