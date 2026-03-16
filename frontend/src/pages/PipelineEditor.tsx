import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, CodeModule } from '../types';

export default function PipelineEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useAuthStore();
  
  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  const [modules, setModules] = useState<CodeModule[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [importModuleId, setImportModuleId] = useState('');
  const [processingModuleIds, setProcessingModuleIds] = useState<string[]>([]);
  const [exportModuleIds, setExportModuleIds] = useState<string[]>([]);
  const [isActive, setIsActive] = useState(false);

  const tenantId = user?.tenantId || user?.id;

  useEffect(() => {
    const fetchData = async () => {
      if (!id || !tenantId) {
        setLoading(false);
        return;
      }
      
      try {
        const [pipelineData, modulesData] = await Promise.all([
          id === 'new' ? Promise.resolve(null) : api.getPipeline(id),
          api.getModules(tenantId),
        ]);
        
        setModules(modulesData);
        
        if (pipelineData) {
          setPipeline(pipelineData);
          setName(pipelineData.name);
          setDescription(pipelineData.description || '');
          setImportModuleId(pipelineData.importModuleVersionId || '');
          setProcessingModuleIds(pipelineData.processingModuleVersionIds || []);
          setExportModuleIds(pipelineData.exportModuleVersionIds || []);
          setIsActive(pipelineData.isActive);
        } else {
          setName('New Pipeline');
          setDescription('');
          setImportModuleId('');
          setProcessingModuleIds([]);
          setExportModuleIds([]);
          setIsActive(false);
        }
      } catch (error) {
        console.error('Error fetching pipeline:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchData();
  }, [id, tenantId]);

  const handleSave = async () => {
    if (!tenantId || !name) return;
    
    setSaving(true);
    try {
      if (id === 'new') {
        const newPipeline = await api.createPipeline(tenantId, name, {
          description,
          importModuleVersionId: importModuleId || undefined,
          processingModuleVersionIds: processingModuleIds,
          exportModuleVersionIds: exportModuleIds,
          isActive,
        });
        navigate(`/pipelines/${newPipeline.id}`);
      } else if (id) {
        await api.updatePipeline(id, {
          name,
          description,
          importModuleVersionId: importModuleId || undefined,
          processingModuleVersionIds: processingModuleIds,
          exportModuleVersionIds: exportModuleIds,
          isActive,
        });
        alert('Pipeline saved successfully!');
      }
    } catch (error) {
      console.error('Error saving pipeline:', error);
      alert('Failed to save pipeline');
    } finally {
      setSaving(false);
    }
  };

  const handleRun = async () => {
    if (!id || id === 'new') return;
    
    try {
      await api.triggerPipeline(id);
      alert('Pipeline triggered successfully!');
    } catch (error) {
      console.error('Error running pipeline:', error);
      alert('Failed to trigger pipeline');
    }
  };

  const addProcessingModule = () => {
    setProcessingModuleIds([...processingModuleIds, '']);
  };

  const updateProcessingModule = (index: number, value: string) => {
    const updated = [...processingModuleIds];
    updated[index] = value;
    setProcessingModuleIds(updated);
  };

  const removeProcessingModule = (index: number) => {
    setProcessingModuleIds(processingModuleIds.filter((_, i) => i !== index));
  };

  const addExportModule = () => {
    setExportModuleIds([...exportModuleIds, '']);
  };

  const updateExportModule = (index: number, value: string) => {
    const updated = [...exportModuleIds];
    updated[index] = value;
    setExportModuleIds(updated);
  };

  const removeExportModule = (index: number) => {
    setExportModuleIds(exportModuleIds.filter((_, i) => i !== index));
  };

  const importModules = modules.filter(m => m.moduleType === 'IMPORT');
  const processingModules = modules.filter(m => m.moduleType === 'PROCESSING');
  const exportModules = modules.filter(m => m.moduleType === 'EXPORT');

  if (loading) {
    return (
      <div className="px-4 py-6">
        <div className="card animate-pulse">
          <div className="h-4 bg-gray-200 rounded w-1/3 mb-4"></div>
        </div>
      </div>
    );
  }

  if (!tenantId) {
    return (
      <div className="px-4 py-6">
        <div className="card text-center py-12">
          <p className="text-gray-500">No tenant ID found. Please contact support.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">
          {id === 'new' ? 'Create Pipeline' : 'Edit Pipeline'}
        </h1>
        <div className="flex space-x-2">
          <button
            onClick={() => navigate('/pipelines')}
            className="btn-secondary"
          >
            Cancel
          </button>
          {id !== 'new' && (
            <button
              onClick={handleRun}
              className="btn-primary bg-green-600 hover:bg-green-700"
            >
              Run Pipeline
            </button>
          )}
          <button
            onClick={handleSave}
            disabled={saving}
            className="btn-primary"
          >
            {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-6">
          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Basic Information</h2>
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  className="input w-full"
                  rows={3}
                />
              </div>
              <div className="flex items-center">
                <input
                  type="checkbox"
                  id="isActive"
                  checked={isActive}
                  onChange={(e) => setIsActive(e.target.checked)}
                  className="h-4 w-4 text-primary-600 border-gray-300 rounded"
                />
                <label htmlFor="isActive" className="ml-2 text-sm text-gray-700">
                  Active (start processing automatically)
                </label>
              </div>
            </div>
          </div>

          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Import Module</h2>
            <select
              value={importModuleId}
              onChange={(e) => setImportModuleId(e.target.value)}
              className="input w-full"
            >
              <option value="">None</option>
              {importModules.map(m => (
                <option key={m.id} value={m.id}>{m.name}</option>
              ))}
            </select>
            {importModules.length === 0 && (
              <p className="text-sm text-gray-500 mt-2">
                No import modules available. Create one in the Modules section.
              </p>
            )}
          </div>

          <div className="card">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-lg font-semibold">Processing Modules</h2>
              <button
                type="button"
                onClick={addProcessingModule}
                className="text-sm text-primary-600 hover:text-primary-700"
              >
                + Add Module
              </button>
            </div>
            {processingModuleIds.length > 0 ? (
              <div className="space-y-2">
                {processingModuleIds.map((moduleId, index) => (
                  <div key={index} className="flex space-x-2">
                    <select
                      value={moduleId}
                      onChange={(e) => updateProcessingModule(index, e.target.value)}
                      className="input flex-1"
                    >
                      <option value="">Select module</option>
                      {processingModules.map(m => (
                        <option key={m.id} value={m.id}>{m.name}</option>
                      ))}
                    </select>
                    <button
                      type="button"
                      onClick={() => removeProcessingModule(index)}
                      className="text-red-600 hover:text-red-700"
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">No processing modules added.</p>
            )}
          </div>

          <div className="card">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-lg font-semibold">Export Modules</h2>
              <button
                type="button"
                onClick={addExportModule}
                className="text-sm text-primary-600 hover:text-primary-700"
              >
                + Add Module
              </button>
            </div>
            {exportModuleIds.length > 0 ? (
              <div className="space-y-2">
                {exportModuleIds.map((moduleId, index) => (
                  <div key={index} className="flex space-x-2">
                    <select
                      value={moduleId}
                      onChange={(e) => updateExportModule(index, e.target.value)}
                      className="input flex-1"
                    >
                      <option value="">Select module</option>
                      {exportModules.map(m => (
                        <option key={m.id} value={m.id}>{m.name}</option>
                      ))}
                    </select>
                    <button
                      type="button"
                      onClick={() => removeExportModule(index)}
                      className="text-red-600 hover:text-red-700"
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">No export modules added.</p>
            )}
          </div>
        </div>

        <div className="space-y-6">
          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Pipeline Info</h2>
            {pipeline ? (
              <div className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-gray-500">Status</span>
                  <span className={isActive ? 'text-green-600' : 'text-gray-600'}>
                    {isActive ? 'Active' : 'Inactive'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">Created</span>
                  <span>{pipeline.createdAt ? new Date(pipeline.createdAt).toLocaleDateString() : 'N/A'}</span>
                </div>
              </div>
            ) : (
              <p className="text-sm text-gray-500">New pipeline</p>
            )}
          </div>

          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Help</h2>
            <div className="text-sm text-gray-600 space-y-2">
              <p><strong>Import Module:</strong> Fetches data from external sources</p>
              <p><strong>Processing Modules:</strong> Transform data (run in order)</p>
              <p><strong>Export Modules:</strong> Send data to external systems</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
