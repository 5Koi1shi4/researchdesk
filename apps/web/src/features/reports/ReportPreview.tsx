import { useState, type ReactNode } from 'react';
import type { Citation } from './reportsApi';
const markers=/\[\[cite:([^\]\s]+)\]\]/g;
export function presentCitations(markdown:string,citations:Citation[]) {
 const known=new Map(citations.map(c=>[c.chunkId,c]));const result:Citation[]=[];const seen=new Set<string>();
 for(const match of markdown.matchAll(markers)){const citation=known.get(match[1]);if(citation&&!seen.has(match[1])){seen.add(match[1]);result.push(citation);}}
 return result;
}
export function ReportPreview({markdown,citations}:{markdown:string;citations:Citation[]}){
 const [selectedChunkId,setSelectedChunkId]=useState<string|null>(null); const present=presentCitations(markdown,citations);const selected=present.find(c=>c.chunkId===selectedChunkId);const numbers=new Map(present.map((c,i)=>[c.chunkId,i+1]));
 function inline(text:string):ReactNode[]{
  // React text nodes keep raw HTML inert. Images and links remain literal text; no resource URL is visited.
  const parts:ReactNode[]=[];let end=0;const tokens=/`[^`]*`|\*\*[^*]+\*\*|\*[^*]+\*|\[\[cite:([^\]\s]+)\]\]/g;
  for(const match of text.matchAll(tokens)){parts.push(text.slice(end,match.index));const token=match[0];
   if(token.startsWith('`'))parts.push(<code key={match.index}>{inline(token.slice(1,-1))}</code>);
   else if(token.startsWith('**'))parts.push(<strong key={match.index}>{inline(token.slice(2,-2))}</strong>);
   else if(token.startsWith('*'))parts.push(<em key={match.index}>{inline(token.slice(1,-1))}</em>);
   else {const c=present.find(c=>c.chunkId===match[1]);parts.push(c?<button key={match.index} type="button" className="citation-marker" aria-label={`引用 ${numbers.get(c.chunkId)}：${c.title}`} onClick={()=>setSelectedChunkId(c.chunkId)}>[{numbers.get(c.chunkId)}]</button>:<span key={match.index} className="error">[引用不可用：{match[1]}]</span>);}
   end=match.index!+match[0].length;
  }
  parts.push(text.slice(end));return parts;
 }
 const lines=markdown.split('\n');const blocks:ReactNode[]=[];
 for(let i=0;i<lines.length;i++){
  const line=lines[i];if(line.startsWith('```')){let code='';while(++i<lines.length&&!lines[i].startsWith('```'))code+=`${lines[i]}\n`;blocks.push(<pre key={i}><code>{inline(code)}</code></pre>);continue;}
  if(!line.trim())continue;const heading=/^(#{1,6})\s+(.*)$/.exec(line);
  if(heading){const level=heading[1].length;blocks.push(level===1?<h2 key={i}>{inline(heading[2])}</h2>:level===2?<h3 key={i}>{inline(heading[2])}</h3>:<h4 key={i}>{inline(heading[2])}</h4>);}
  else if(line.includes('|')&&i+1<lines.length&&/^\s*\|?\s*:?-+/.test(lines[i+1])){
   const cells=(row:string)=>row.replace(/^\s*\||\|\s*$/g,'').split('|');const head=cells(line);i++;const rows:string[][]=[];while(i+1<lines.length&&lines[i+1].includes('|'))rows.push(cells(lines[++i]));blocks.push(<div className="report-table" key={i}><table><thead><tr>{head.map((c,n)=><th key={n}>{inline(c)}</th>)}</tr></thead><tbody>{rows.map((row,n)=><tr key={n}>{row.map((c,j)=><td key={j}>{inline(c)}</td>)}</tr>)}</tbody></table></div>);
  }else if(/^[-*+]\s/.test(line)){const items=[line.slice(2)];while(i+1<lines.length&&/^[-*+]\s/.test(lines[i+1]))items.push(lines[++i].slice(2));blocks.push(<ul key={i}>{items.map((item,n)=><li key={n}>{inline(item)}</li>)}</ul>);}
  else if(/^>\s?/.test(line))blocks.push(<blockquote key={i}>{inline(line.replace(/^>\s?/,''))}</blockquote>);
  else blocks.push(<p key={i}>{inline(line)}</p>);
 }
 return <><article className="report-preview" aria-label="报告预览">{blocks.length?blocks:<p>开始撰写报告，预览会显示在这里。</p>}{present.length?<section aria-label="引用附录"><h3>引用资料</h3><ol>{present.map(c=><li key={c.chunkId}>{c.title}{c.page!==null?` · 第 ${c.page} 页`:c.paragraph!==null?` · 第 ${c.paragraph} 段`:''}{c.availability==='DELETED'?' · 来源已删除':''}</li>)}</ol></section>:null}</article>{selected?<aside className="report-evidence" aria-label="引用摘录"><h3>{selected.title}</h3><p>{selected.availability==='DELETED'?'来源已删除，原文与摘录无法访问。':'保存时的引用摘录'}</p>{selected.availability==='DELETED'?null:<blockquote>{selected.excerpt||'没有保存摘录。'}</blockquote>}<button className="secondary" onClick={()=>setSelectedChunkId(null)}>关闭摘录</button></aside>:null}</>;
}
