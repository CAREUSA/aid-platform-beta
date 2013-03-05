require "rubygems"
require "json"
require "helpers/formatters"
require "helpers/country_helpers"
require "helpers/frontpage_helpers"
require "helpers/project_helpers"
require "helpers/lookups"
require "middleman-smusher"

#------------------------------------------------------------------------------
# CONFIGURATION VARIABLES
#------------------------------------------------------------------------------
@api_root_url   = ENV['DFID_API_URL'] || "http://localhost:9000"
@api_access_url = "#{@api_root_url}/access"
@cms_client     = Mongo::MongoClient.new('localhost', 27017)
@cms_db         = @cms_client['dfid']

#------------------------------------------------------------------------------
# IGNORE TEMPLATES AND PARTIALS
#------------------------------------------------------------------------------
ignore "/countries/country.html"
ignore "/countries/projects.html"

ignore "/projects/summary.html"
ignore "/projects/documents.html"
ignore "/projects/transactions.html"
ignore "/projects/partners.html"

#------------------------------------------------------------------------------
# GENERATE COUNTRIES
#------------------------------------------------------------------------------
@cms_db['countries'].find({}).each do |country|
  stats = @cms_db['country-stats'].find_one({ "code" => country["code"] })

  proxy "/countries/#{country['code']}/index.html",          "/countries/country.html",  :locals => { :country => country, :stats   => stats }
  proxy "/countries/#{country['code']}/projects/index.html", "/countries/projects.html", :locals => { :country => country }
end

#------------------------------------------------------------------------------
# GENERATE PROJECTS
#------------------------------------------------------------------------------
@cms_db['projects'].find({}).each do |project|

  documents           = @cms_db['documents'].find({ 'project' => project['iatiId'] }).to_a
  funded_projects     = @cms_db['funded-projects'].find({ 'funding' => project['iatiId'] }).to_a
  has_funded_projects = funded_projects.size > 0


  proxy "/projects/#{project['iatiId']}/index.html",              '/projects/summary.html',      :locals => { :project => project, :has_funded_projects => has_funded_projects }
  proxy "/projects/#{project['iatiId']}/documents/index.html",    '/projects/documents.html',    :locals => { :project => project, :has_funded_projects => has_funded_projects, :documents => documents }
  proxy "/projects/#{project['iatiId']}/transactions/index.html", '/projects/transactions.html', :locals => { :project => project, :has_funded_projects => has_funded_projects }

  if has_funded_projects then
    proxy "/projects/#{project['iatiId']}/partners/index.html", '/projects/partners.html', :locals => { :project => project, :funded_projects => funded_projects }
  end
end

#------------------------------------------------------------------------------
# GENERATE FUNDED PROJECT PAGES
#------------------------------------------------------------------------------
@cms_db['funded-projects'].find({}).each do |funded_project|

  # format the project model to suit the project templates
  project = {
    'iatiId'      => funded_project['funded'],
    'title'       => funded_project['title'],
    'description' => funded_project['description'],
    'funds'       => funded_project['funds']
  }

  # get the other funded projects
  funded_projects = @cms_db['funded-projects'].find({ 
    'funding' => funded_project['funding'],
    'funded'  => { '$ne' => funded_project['funded'] } 
  }).to_a

  # get the parent project
  funding_project = @cms_db['projects'].find_one({ 'iatiId' =>  funded_project['funding'] })

  proxy "/projects/#{project['iatiId']}/index.html",              '/projects/summary.html',      :locals => { :project => project, :has_funded_projects => true }
  proxy "/projects/#{project['iatiId']}/documents/index.html",    '/projects/documents.html',    :locals => { :project => project, :has_funded_projects => true }
  proxy "/projects/#{project['iatiId']}/transactions/index.html", '/projects/transactions.html', :locals => { :project => project, :has_funded_projects => true }
  proxy "/projects/#{project['iatiId']}/partners/index.html",     '/projects/partners.html',     :locals => { :project => project, :has_funded_projects => true, :funded_projects => funded_projects, :funding_project => funding_project }

end

#------------------------------------------------------------------------------
# DEFINE HELPERS - Import from modules to avoid bloat
#------------------------------------------------------------------------------
helpers do

  include Formatters
  include CountryHelpers
  include FrontPageHelpers
  include Lookups

end

#------------------------------------------------------------------------------
# CONFIGURE DIRECTORIES
#------------------------------------------------------------------------------
set :css_dir   , 'stylesheets'
set :js_dir    , 'javascripts'
set :images_dir, 'images'

activate :livereload

#------------------------------------------------------------------------------
# BUILD SPECIFIC CONFIGURATION
#------------------------------------------------------------------------------
configure :build do
  activate :minify_css
  activate :minify_javascript
  activate :cache_buster
  
  # this takes time, be careful
  # activate :smusher
end