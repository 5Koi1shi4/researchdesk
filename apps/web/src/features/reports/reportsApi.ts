import { api } from '../../api';
import type { components } from '../../generated/contracts';
export type Citation=components['schemas']['Citation'];
export type Report=components['schemas']['Report'];
export type ReportVersion=components['schemas']['ReportVersion'];
export type ReportExport=components['schemas']['ReportExport'];
const path=(id:string)=>`/api/reports/${encodeURIComponent(id)}`;
export const reportsApi={
 fromRun:(runId:string)=>api<Report>(`/api/runs/${encodeURIComponent(runId)}/report`),
 list:(projectId:string)=>api<Report[]>(`/api/projects/${encodeURIComponent(projectId)}/reports`),
 create:(projectId:string,title:string,csrf:string)=>api<ReportVersion>(`/api/projects/${encodeURIComponent(projectId)}/reports`,'POST',{title,markdown:''},csrf),
 get:(id:string)=>api<Report>(path(id)),history:(id:string)=>api<ReportVersion[]>(`${path(id)}/versions`),version:(id:string,version:number)=>api<ReportVersion>(`${path(id)}/versions/${version}`),
 save:(id:string,baseVersion:number,markdown:string,csrf:string)=>api<ReportVersion>(`${path(id)}/versions`,'POST',{baseVersion,markdown},csrf),
 copy:(id:string,version:number,markdown:string,csrf:string)=>api<ReportVersion>(`${path(id)}/copies`,'POST',{version,markdown},csrf),
 export:(id:string,version:number,format:ReportExport['format'],csrf:string)=>api<ReportExport>(`${path(id)}/exports`,'POST',{version,format},csrf),
 exportStatus:(id:string,exportId:string)=>api<ReportExport>(`${path(id)}/exports/${encodeURIComponent(exportId)}`),
 download:(id:string,exportId:string)=>`${path(id)}/exports/${encodeURIComponent(exportId)}/download`
};
export function allowReportLeave(){const event=new Event('report-before-leave',{cancelable:true});window.dispatchEvent(event);return !event.defaultPrevented;}
