import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { User } from '../types';

export default function Users() {
  const { user } = useAuthStore();
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [showInviteModal, setShowInviteModal] = useState(false);
  const [newUserEmail, setNewUserEmail] = useState('');
  const [newUserPassword, setNewUserPassword] = useState('');
  const [newUserFirstName, setNewUserFirstName] = useState('');
  const [newUserLastName, setNewUserLastName] = useState('');
  const [inviting, setInviting] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const tenantId = user?.tenantId || user?.id;

  useEffect(() => {
    const fetchUsers = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      
      try {
        const data = await api.getUsers(tenantId);
        setUsers(data);
      } catch (error) {
        console.error('Error fetching users:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchUsers();
  }, [tenantId]);

  const handleInviteUser = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId || !newUserEmail || !newUserPassword) return;
    
    setInviting(true);
    try {
      await api.createUser(tenantId, newUserEmail, newUserPassword, {
        firstName: newUserFirstName,
        lastName: newUserLastName,
      });
      setShowInviteModal(false);
      setNewUserEmail('');
      setNewUserPassword('');
      setNewUserFirstName('');
      setNewUserLastName('');
      // Refresh users list
      const data = await api.getUsers(tenantId);
      setUsers(data);
    } catch (error) {
      console.error('Error inviting user:', error);
    } finally {
      setInviting(false);
    }
  };

  const handleDeleteUser = async (userId: string) => {
    if (!confirm('Are you sure you want to delete this user?')) return;
    
    setDeletingId(userId);
    try {
      await api.deleteUser(userId);
      setUsers(users.filter(u => u.id !== userId));
    } catch (error) {
      console.error('Error deleting user:', error);
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Users</h1>
        <button onClick={() => setShowInviteModal(true)} className="btn-primary">
          Invite User
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
      ) : !tenantId ? (
        <div className="card text-center py-12">
          <p className="text-gray-500">No tenant ID found. Please contact support.</p>
        </div>
      ) : (
        <div className="card overflow-hidden">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                  User
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                  Role
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                  Abilities
                </th>
                <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-200">
              {users.map((u) => (
                <tr key={u.id}>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <div className="flex items-center">
                      <div className="h-10 w-10 rounded-full bg-primary-100 flex items-center justify-center">
                        <span className="text-primary-600 font-medium">
                          {u.email[0].toUpperCase()}
                        </span>
                      </div>
                      <div className="ml-4">
                        <div className="text-sm font-medium text-gray-900">{u.email}</div>
                        <div className="text-sm text-gray-500">
                          {u.firstName} {u.lastName}
                        </div>
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span className="px-2 py-1 text-xs font-medium rounded bg-gray-100 text-gray-800">
                      {u.roleName || 'No Role'}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex flex-wrap gap-1">
                      {(u.abilities || []).slice(0, 3).map((ability) => (
                        <span key={ability} className="px-2 py-0.5 text-xs rounded bg-blue-100 text-blue-800">
                          {ability}
                        </span>
                      ))}
                      {(u.abilities?.length || 0) > 3 && (
                        <span className="px-2 py-0.5 text-xs rounded bg-gray-100 text-gray-600">
                          +{(u.abilities?.length || 0) - 3}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                    <button className="text-primary-600 hover:text-primary-900 mr-4">Edit</button>
                    <button 
                      onClick={() => handleDeleteUser(u.id)}
                      disabled={deletingId === u.id}
                      className="text-red-600 hover:text-red-900"
                    >
                      {deletingId === u.id ? 'Deleting...' : 'Delete'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showInviteModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">Invite User</h2>
            <form onSubmit={handleInviteUser}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Email</label>
                <input
                  type="email"
                  value={newUserEmail}
                  onChange={(e) => setNewUserEmail(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="mb-4 grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">First Name</label>
                  <input
                    type="text"
                    value={newUserFirstName}
                    onChange={(e) => setNewUserFirstName(e.target.value)}
                    className="input w-full"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Last Name</label>
                  <input
                    type="text"
                    value={newUserLastName}
                    onChange={(e) => setNewUserLastName(e.target.value)}
                    className="input w-full"
                  />
                </div>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Temporary Password</label>
                <input
                  type="password"
                  value={newUserPassword}
                  onChange={(e) => setNewUserPassword(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => setShowInviteModal(false)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={inviting}
                  className="btn-primary"
                >
                  {inviting ? 'Inviting...' : 'Invite'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
