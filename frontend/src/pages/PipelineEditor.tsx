import { useParams, useNavigate } from 'react-router-dom';

export default function PipelineEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">
          Pipeline Editor
        </h1>
        <button
          onClick={() => navigate('/pipelines')}
          className="btn-secondary"
        >
          Back to Pipelines
        </button>
      </div>

      <div className="card">
        <p className="text-gray-500">
          Pipeline editor for pipeline {id} - Coming soon
        </p>
      </div>
    </div>
  );
}
